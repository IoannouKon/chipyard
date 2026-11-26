# Guard each linked and optimized design before trusting timing or power.
# All physical optimization is enabled. Nothing here applies DONT_TOUCH/LOCK/LOC.
proc retention_guard {out stage expected_luts expected_ffs expected_bram36} {
    set cells [get_cells -hier -filter {IS_PRIMITIVE && NAME =~ core/*}]
    set primitive_lut_count [llength [filter $cells {REF_NAME =~ LUT*}]]
    set lut -1
    set ff [llength [filter $cells {REF_NAME =~ FD*}]]
    set b36 [llength [filter $cells {REF_NAME =~ RAMB36*}]]
    set b18 [llength [filter $cells {REF_NAME =~ RAMB18*}]]
    set dsp [llength [filter $cells {REF_NAME =~ DSP*}]]
    set adders [get_cells -quiet -hier -filter {NAME =~ core/outputMemory/sum_add*}]
    set gen [get_cells -quiet -hier -filter {NAME =~ core/Product_Generator_Mesh*}]
    set hp [file join $out utilization_hierarchical_${stage}.rpt]
    report_utilization -hierarchical -file $hp
    set hf [open $hp r];set hs [read $hf];close $hf
    foreach line [split $hs "\n"] {
        set fields [split $line "|"]
        if {[llength $fields]>4 && [string trim [lindex $fields 1]] eq "core" && [string trim [lindex $fields 2]] eq "LUMIXEDExample"} {
            set lut [string map {, ""} [string trim [lindex $fields 3]]]
        }
    }
    if {$lut<0} {error "No authoritative core LUT hierarchy row"}
    set f [open [file join $out retention_${stage}.tsv] w]
    puts $f "metric\tobserved\tstandalone_reference"
    foreach {label value expect} [list core_luts $lut $expected_luts core_ff $ff $expected_ffs core_bram36 $b36 $expected_bram36 core_bram18 $b18 0 core_dsp $dsp 0 adder_hierarchy_cells [llength $adders] 1 product_hierarchy_cells [llength $gen] 1] {puts $f "$label\t$value\t$expect"}
    # Report top/context overhead independently through hierarchical utilization.
    puts $f "primitive_lut_objects\t$primitive_lut_count\tnot_a_physical_LUT_count"
    # Use the authoritative hierarchy LUT count; pre-opt netlists may split
    # dual-output LUTs into multiple logical cells, so raw LUT object count is not comparable.
    set ok [expr {$lut >= 0.95*$expected_luts && $ff >= 0.95*$expected_ffs && $b36 == $expected_bram36 && [llength $adders]>0 && [llength $gen]>0}]
    if {$stage eq "linked"} {set ok [expr {$ok && $lut == $expected_luts && $ff == $expected_ffs}]}
    puts $f "retained_full_core\t$ok\t1"
    close $f
    puts "RETENTION_CHECK $stage LUT=$lut FF=$ff BRAM36=$b36 EXPECTED=$expected_luts,$expected_ffs,$expected_bram36 PASS=$ok"
    if {!$ok} {error "Accelerator retention regression at $stage: do not report PPA"}
}
# Check that the linked TileLink data and registered output observation exist.
# End-to-end data provenance is exercised separately by the Verilator smoke tests,
# which compare registered write payloads and memory readback to the references.
proc data_reachability {out} {
    set capture [get_pins -quiet -hier -filter {NAME =~ lastWriteData_reg*/D}]
    set outputs [get_ports -quiet io_lastWriteData*]
    set core_pin [get_pins -quiet -of_objects [get_cells core] -filter {DIRECTION == OUT && NAME =~ core/auto_tl_out_a_bits_data*}]
    set f [open [file join $out data_reachability.tsv] w]
    puts $f "check\tcount"
    puts $f "registered_write_capture_d_pins\t[llength $capture]"
    puts $f "registered_write_output_ports\t[llength $outputs]"
    puts $f "linked_core_tilelink_write_data_pins\t[llength $core_pin]"
    close $f
    if {[llength $capture] != 64 || [llength $outputs] != 64 || [llength $core_pin] != 64} {
        error "Expected 64-bit TileLink write data and registered observation"
    }
}
