# Implementation-only common global-clock integration. Datapath RTL is untouched.
proc ppa_integrate_clock {clock_port} {
    set port [get_ports $clock_port]
    if {[llength $port] != 1} {error "Expected one scalar clock port"}
    if {[llength [get_cells -quiet PPA_GLOBAL_CLOCK_BUFFER]]} {return}
    set net [get_nets -of_objects $port]
    if {[llength $net] != 1} {error "Expected one existing DUT clock net"}
    create_cell -reference BUFGCE PPA_GLOBAL_CLOCK_BUFFER
    create_cell -reference VCC PPA_GLOBAL_CLOCK_ENABLE
    disconnect_net -net $net -objects $port
    create_net PPA_GLOBAL_CLOCK_INPUT
    create_net PPA_GLOBAL_CLOCK_ENABLE_NET
    connect_net -net PPA_GLOBAL_CLOCK_INPUT -objects [list $port [get_pins PPA_GLOBAL_CLOCK_BUFFER/I]]
    connect_net -net $net -objects [get_pins PPA_GLOBAL_CLOCK_BUFFER/O]
    connect_net -net PPA_GLOBAL_CLOCK_ENABLE_NET -objects [list [get_pins PPA_GLOBAL_CLOCK_ENABLE/P] [get_pins PPA_GLOBAL_CLOCK_BUFFER/CE]]
    puts "PPA_CLOCK_INTEGRATION BUFGCE=1 CE=constant1; all original DUT clock loads unchanged"
}
