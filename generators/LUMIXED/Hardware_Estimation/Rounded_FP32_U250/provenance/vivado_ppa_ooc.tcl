# Generic standalone Vivado OOC PPA flow; requires elaborated, self-contained RTL.
# This Tcl does NOT generate Chipyard RTL and cannot validate RTL behavior.
# Usage: vivado -mode batch -source vivado_ppa_ooc.tcl -tclargs \
#   <top> <sources.txt> <incdirs.txt> <part> <clock_port> <clock_period_ns> <synth|route> <output_dir>
if {[llength $argv] != 8} {
    puts stderr "Usage: <top> <sources.txt> <incdirs.txt> <part> <clock_port> <period_ns> <synth|route> <output_dir>"
    exit 9
}
lassign $argv TOP SRC_LIST INC_LIST PART CLOCK_PORT PERIOD_NS STAGE OUTDIR
file mkdir $OUTDIR
set OUTDIR [file normalize $OUTDIR]
set STATUS_FILE [file join $OUTDIR status.tsv]
set STATUS "INIT"
set COMPLETED_STAGE "none"
set FAILURE ""
set CLOCK_FOUND 0
set WNS ""
set WHS ""
set FMAX ""
set BLACKBOXES 0
set REUSED_SYNTH 0
proc save_status {} {
    global STATUS_FILE STATUS COMPLETED_STAGE FAILURE TOP PART CLOCK_PORT PERIOD_NS CLOCK_FOUND WNS WHS FMAX BLACKBOXES
    set f [open $STATUS_FILE w]
    foreach {k v} [list status $STATUS stage $COMPLETED_STAGE failure $FAILURE top $TOP part $PART \
                    clock_port $CLOCK_PORT period_ns $PERIOD_NS clock_found $CLOCK_FOUND \
                    worst_setup_slack_ns $WNS worst_hold_slack_ns $WHS \
                    estimated_fmax_mhz $FMAX blackbox_count $BLACKBOXES] {
        regsub -all {[\r\n\t]} "$v" { } v
        puts $f "$k\t$v"
    }
    close $f
}
proc fail {status message exitcode} {
    global STATUS FAILURE
    set STATUS $status
    set FAILURE $message
    save_status
    puts stderr "VIVADO_PPA_FAILURE ${status}: $message"
    exit $exitcode
}
proc read_lines {file_name} {
    set f [open $file_name r]; set raw [read $f]; close $f
    set out {}
    foreach line [split $raw "\n"] {
        set v [string trim $line]
        if {$v ne "" && ![string match "#*" $v]} {lappend out $v}
    }
    return $out
}
proc primitive_counts {out} {
    global COMPLETED_STAGE
    # Cross-check counts against authoritative report_utilization reports.
    set f [open [file join $out primitive_counts.tsv] w]
    puts $f "category\tcell_count"
    foreach {cat pat} {
        LUT "REF_NAME =~ LUT*"
        FF "REF_NAME =~ FD*"
        DSP "REF_NAME =~ DSP*"
        BRAM18 "REF_NAME =~ RAMB18*"
        BRAM36 "REF_NAME =~ RAMB36*"
        URAM "REF_NAME =~ URAM*"
    } {
        set cells [get_cells -quiet -hierarchical -filter $pat]
        puts $f "$cat\t[llength $cells]"
    }
    close $f
    file copy -force [file join $out primitive_counts.tsv] [file join $out primitive_counts_${COMPLETED_STAGE}.tsv]
}
if {$STAGE ni {synth route}} {fail "ARGUMENT_ERROR" "stage must be synth or route" 9}
if {![file exists $SRC_LIST]} {fail "INPUT_ERROR" "missing sources list: $SRC_LIST" 9}
if {![file exists $INC_LIST]} {fail "INPUT_ERROR" "missing include dirs list: $INC_LIST" 9}
if {[catch {create_project -in_memory -part $PART ppa_design} err]} {
    fail "PART_ERROR" $err 9
}
set_property target_language Verilog [current_project]
if {[info exists ::env(PPA_MAX_THREADS)]} {
    set_param general.maxThreads $::env(PPA_MAX_THREADS)
}
set dirs [read_lines $INC_LIST]
foreach d $dirs {if {![file isdirectory $d]} {fail INPUT_ERROR "include directory missing: $d" 9}}
if {[llength $dirs]} {set_property include_dirs $dirs [current_fileset]}
set sources [read_lines $SRC_LIST]
if {![llength $sources]} {fail INPUT_ERROR "no HDL sources listed" 9}
foreach src $sources {
    if {![file exists $src]} {fail INPUT_ERROR "RTL source missing: $src" 9}
    set ext [string tolower [file extension $src]]
    if {$ext in {.v .sv}} {
        if {[catch {read_verilog -sv $src} err]} {fail READ_RTL_FAIL $err 8}
    } elseif {$ext in {.vhd .vhdl}} {
        if {[catch {read_vhdl $src} err]} {fail READ_RTL_FAIL $err 8}
    } elseif {$ext in {.edf .edif}} {
        if {[catch {read_edif $src} err]} {fail READ_RTL_FAIL $err 8}
    } elseif {$ext eq ".xci"} {
        if {[catch {read_ip $src} err]} {fail READ_IP_FAIL $err 8}
    } else {fail INPUT_ERROR "unsupported RTL/IP extension $ext: $src" 9}
}
puts "VIVADO_PPA TOP=$TOP PART=$PART STAGE=$STAGE CLOCK=$CLOCK_PORT PERIOD=$PERIOD_NS"
# synth_design -mode out_of_context intentionally bypasses board-specific I/O.
if {[info exists ::env(PPA_REUSE_LINKED)] && $::env(PPA_REUSE_LINKED) eq "1"} {
    if {[catch {open_checkpoint [file join $OUTDIR post_synth.dcp]} err]} {fail OPEN_CHECKPOINT_FAIL $err 5}
    set REUSED_SYNTH 1
} else {
# Synthesize context only. Its accelerator is the exact stub extracted from DCP.
if {[catch {synth_design -top $TOP -part $PART -mode out_of_context -flatten_hierarchy none} err]} {fail WRAPPER_SYNTH_FAIL $err 4}
report_utilization -hierarchical -file [file join $OUTDIR context_blackbox_utilization.rpt]
write_checkpoint -force [file join $OUTDIR context_blackbox.dcp]
set bb_core [get_cells -quiet core]
if {[llength $bb_core] != 1 || ![get_property IS_BLACKBOX $bb_core]} {fail LINK_INPUT_FAIL "Expected exactly one black-box core" 4}
# Import this complete standalone synthesis netlist using standard EDIF linking.
# write_edif exports supported EDIF; the raw .edf member inside a DCP is private
# checkpoint encoding and must not be passed directly to read_edif.
write_edif -force [file join $OUTDIR RegisteredContext.edf]
close_project
create_project -in_memory -part $PART ppa_linked_design
read_edif [file join $OUTDIR RegisteredContext.edf]
read_edif $::env(PPA_CORE_EDIF)
if {[catch {link_design -mode out_of_context -top $TOP -part $PART} err]} {fail CORE_LINK_FAIL $err 4}
}

# No inherited OOC virtual I/O timing survives on the internal core interface.
# This is followed by the one real 20 ns top clock and the common constraints.
if {[catch {reset_timing} err]} {fail CONSTRAINT_RESET_FAIL $err 4}
set_param general.maxThreads $::env(PPA_MAX_THREADS)
set_property DONT_TOUCH false [get_cells core]
set core_property_file [open [file join $OUTDIR imported_core_properties.rpt] w]
foreach property {NAME REF_NAME DONT_TOUCH IS_BLACKBOX} {
    puts $core_property_file "$property\t[get_property $property [get_cells core]]"
}
close $core_property_file
set fixed [get_cells -quiet -hier -filter {IS_PRIMITIVE && NAME =~ core/* && IS_LOC_FIXED == 1}]
if {[llength $fixed]} {fail LOCKED_CORE_FAIL "Imported synthesized core unexpectedly has fixed primitive placement" 4}
puts "CORE_PHYSICAL_OPTIMIZATION root_DONT_TOUCH=[get_property DONT_TOUCH [get_cells core]] fixed_locations=[llength $fixed]"

source [file join [file dirname [info script]] retention.tcl]
if {[catch {retention_guard $OUTDIR linked $::env(PPA_EXPECT_LUTS) $::env(PPA_EXPECT_FFS) 56} err]} {fail RETENTION_FAIL $err 4}
if {[catch {data_reachability $OUTDIR} err]} {fail REACHABILITY_FAIL $err 4}
write_xdc -force [file join $OUTDIR after_link_constraint_reset.xdc]
set STATUS SYNTH_PASS
set COMPLETED_STAGE post_synthesis
if {!$REUSED_SYNTH} {catch {write_checkpoint -force [file join $OUTDIR post_synth.dcp]}}
if {[catch {report_utilization -file [file join $OUTDIR utilization_post_synth.rpt]} err]} {
    fail SYNTH_REPORT_FAIL $err 4
}
catch {report_utilization -hierarchical -file [file join $OUTDIR utilization_hierarchical_post_synth.rpt]}
primitive_counts $OUTDIR
save_status
# Reject unresolved black-box logic; estimates would undercount actual hardware.
set bb [get_cells -quiet -hierarchical -filter {IS_BLACKBOX == 1}]
set BLACKBOXES [llength $bb]
if {$BLACKBOXES > 0} {
    set f [open [file join $OUTDIR blackboxes.txt] w]
    foreach c $bb {puts $f $c}
    close $f
    fail INVALID_BLACKBOX "Found $BLACKBOXES black boxes; do not use underestimated PPA" 4
}
set clocks [get_ports -quiet $CLOCK_PORT]
if {[llength $clocks] == 1} {
    if {![llength [get_clocks -quiet ppa_clk]]} {
        if {[catch {create_clock -name ppa_clk -period $PERIOD_NS $clocks} err]} {fail CLOCK_ERROR $err 4}
    }
    # Common OOC boundary assumption: synchronous ideal external I/O, no board pins.
    set data_inputs {}
    set resets [get_ports -quiet reset]
    foreach pin [all_inputs] {
        if {[lsearch -exact $clocks $pin] < 0 && [lsearch -exact $resets $pin] < 0} {lappend data_inputs $pin}
    }
    if {[llength $resets]} {set_false_path -from $resets}
    if {[llength $data_inputs]} {set_input_delay 0 -clock ppa_clk $data_inputs}
    if {[llength [all_outputs]]} {set_output_delay 0 -clock ppa_clk [all_outputs]}
    set CLOCK_FOUND 1
    # Preserve constrained synthesis for source/version-validated route resumption.
    if {!$REUSED_SYNTH} {write_checkpoint -force [file join $OUTDIR post_synth.dcp]}
} else {
    puts stderr "CLOCK_WARNING: exact input port '$CLOCK_PORT' not found; synthesis counts only, timing invalid"
}
if {$CLOCK_FOUND && (!$REUSED_SYNTH || ![file exists [file join $OUTDIR timing_post_synth_PRELIMINARY.rpt]])} {
    catch {report_timing_summary -report_unconstrained -file [file join $OUTDIR timing_post_synth_PRELIMINARY.rpt]}
}
# Power without route or switching activity is HIGH uncertainty and is labeled preliminary.
if {!$REUSED_SYNTH || ![file exists [file join $OUTDIR power_post_synth_PRELIMINARY.rpt]]} {
    catch {report_power -file [file join $OUTDIR power_post_synth_PRELIMINARY.rpt]}
}
save_status
if {$STAGE eq "synth"} {
    puts "VIVADO_PPA_SYNTHESIS_COMPLETE"
    exit 0
}
if {!$CLOCK_FOUND} {
    fail INVALID_CLOCK "Missing explicit clock: cannot give defensible routed timing" 5
}
# Common FPGA integration, outside the accelerator: route a real, always-enabled
# global clock tree. OOC synthesis otherwise leaves the clock buffer external.
# Original datapath/storage and the saved accelerator-only synthesis are retained.
source [file join [file dirname [info script]] ooc_clock_integration.tcl]
if {[catch {ppa_integrate_clock $CLOCK_PORT} err]} {fail CLOCK_INTEGRATION_FAIL $err 5}
foreach {cmd label} {
    opt_design OPT_FAIL
    place_design PLACE_FAIL
    phys_opt_design PHYS_OPT_FAIL
    route_design ROUTE_FAIL
} {
    puts "VIVADO_PPA_STAGE $cmd"
    if {[catch {uplevel #0 $cmd} err]} {fail $label $err 5}
    if {$cmd eq "opt_design" || $cmd eq "phys_opt_design" || $cmd eq "route_design"} {
        if {[catch {retention_guard $OUTDIR $cmd $::env(PPA_EXPECT_LUTS) $::env(PPA_EXPECT_FFS) 56} err]} {fail RETENTION_FAIL $err 4}
    }
    if {$cmd eq "place_design"} {
        set COMPLETED_STAGE placed
        catch {write_checkpoint -force [file join $OUTDIR post_place.dcp]}
        catch {report_timing_summary -file [file join $OUTDIR timing_post_place.rpt]}
        catch {report_utilization -file [file join $OUTDIR utilization_post_place.rpt]}
        primitive_counts $OUTDIR
        save_status
    }
}
if {![report_route_status -boolean_check ROUTED_FULLY] || [report_route_status -boolean_check ERRORS_IN_ROUTES]} {fail ROUTE_INCOMPLETE "Actual routing status is not fully routed and clean" 5}
set STATUS ROUTED
set COMPLETED_STAGE post_route
catch {write_checkpoint -force [file join $OUTDIR post_route.dcp]}
catch {report_route_status -file [file join $OUTDIR route_status.rpt]}
catch {report_utilization -hierarchical -file [file join $OUTDIR utilization_hierarchical_post_route.rpt]}
if {[catch {report_utilization -file [file join $OUTDIR utilization_post_route.rpt]} err]} {
    fail ROUTE_REPORT_FAIL $err 6
}
primitive_counts $OUTDIR
catch {report_timing_summary -report_unconstrained -max_paths 10 -file [file join $OUTDIR timing_post_route.rpt]}
catch {report_power -file [file join $OUTDIR power_post_route_vectorless.rpt]}
catch {report_power -hier all -file [file join $OUTDIR power_hierarchy_post_route.rpt]}
catch {write_xdc -force [file join $OUTDIR final_constraints.xdc]}
# Additional diagnostics only; no RTL, constraints or scheduling changes.
puts "PPA_THREAD_CONFIGURATION maxThreads=[get_param general.maxThreads]"
set diagnostic_file [open [file join $OUTDIR internal_timing.tsv] w]
set diagnostic_registers [all_registers]
foreach {label delay} {internal_setup max internal_hold min} {
    if {[catch {
        set paths [get_timing_paths -quiet -from $diagnostic_registers -to $diagnostic_registers -max_paths 1 -delay_type $delay]
        if {[llength $paths]} {puts $diagnostic_file "${label}_slack_ns\t[get_property SLACK [lindex $paths 0]]"}
        report_timing -from $diagnostic_registers -to $diagnostic_registers -delay_type $delay -max_paths 10 -file [file join $OUTDIR ${label}.rpt]
    } message]} {puts stderr "PPA_DIAGNOSTIC_UNAVAILABLE $label: $message"}
}
if {[llength $data_inputs]} {
    catch {report_timing -from $data_inputs -delay_type min -max_paths 10 -file [file join $OUTDIR input_hold.rpt]}
}
if {[llength [all_outputs]]} {
    catch {report_timing -to [all_outputs] -delay_type min -max_paths 10 -file [file join $OUTDIR output_hold.rpt]}
}
close $diagnostic_file
set core_registers [filter [all_registers] {NAME =~ core/*}]
if {[llength $core_registers]} {
    foreach {label delay} {core_internal_setup max core_internal_hold min} {
        catch {report_timing -from $core_registers -to $core_registers -delay_type $delay -max_paths 10 -file [file join $OUTDIR ${label}.rpt]}
    }
}
catch {report_clocks -file [file join $OUTDIR clocks.rpt]}
catch {report_clock_utilization -file [file join $OUTDIR clock_utilization.rpt]}
catch {report_drc -file [file join $OUTDIR drc_post_route.rpt]}

set setup_paths [get_timing_paths -quiet -max_paths 1 -delay_type max]
if {[llength $setup_paths]} {
    set WNS [get_property SLACK [lindex $setup_paths 0]]
}
# Internal same-clock Fmax excludes unconstrained/external interface budget claims.
set internal_paths [get_timing_paths -quiet -from [all_registers] -to [all_registers] -max_paths 1 -delay_type max]
if {[llength $internal_paths]} {
    set internal_wns [get_property SLACK [lindex $internal_paths 0]]
    set sc [get_property STARTPOINT_CLOCK [lindex $internal_paths 0]]
    set ec [get_property ENDPOINT_CLOCK [lindex $internal_paths 0]]
    set req [get_property REQUIREMENT [lindex $internal_paths 0]]
    if {$sc ne "" && $sc eq $ec && abs(double($req)-double($PERIOD_NS)) < 0.001 && ($PERIOD_NS - $internal_wns) > 0} {
        set FMAX [format "%.3f" [expr {1000.0 / (double($PERIOD_NS) - double($internal_wns))}]]
    }
}
catch {check_timing -verbose -file [file join $OUTDIR check_timing_post_route.rpt]}
set hold_paths [get_timing_paths -quiet -max_paths 1 -delay_type min]
if {[llength $hold_paths]} {set WHS [get_property SLACK [lindex $hold_paths 0]]}
if {$WNS eq "" || $WHS eq ""} {
    set STATUS ROUTED_TIMING_UNVERIFIED
} elseif {$WNS < 0 || $WHS < 0} {
    set STATUS ROUTED_TIMING_FAIL
} else {
    set STATUS ROUTED_TIMING_PASS
}
save_status
puts "VIVADO_PPA_COMPLETE $STATUS (do not interpret internal Fmax as verified system clock)"
exit 0
