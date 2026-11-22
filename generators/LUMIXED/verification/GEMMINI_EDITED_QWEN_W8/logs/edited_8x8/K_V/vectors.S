.section .rodata.vectors,"a",@progbits
.balign 64
.global inputs
inputs:
.incbin "x.bin"
.balign 64
.global weights
weights:
.incbin "w.bin"
.section .note.GNU-stack,"",@progbits
