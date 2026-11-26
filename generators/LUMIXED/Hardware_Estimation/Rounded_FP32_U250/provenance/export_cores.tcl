set_param general.maxThreads 8
set R [file normalize [file join [file dirname [info script]] ..]]
foreach mode {rounded pipelined} {
    open_checkpoint $R/cores/$mode/post_synth.dcp
    write_edif -force $R/cores/$mode/LUMIXEDExample.edf
    report_utilization -file $R/cores/$mode/export_source_utilization.rpt
    close_project
}
exit
