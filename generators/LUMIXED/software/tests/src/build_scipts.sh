#!/bin/bash

# Paths
SRC="Linear-sw.c"
OUT_DIR="builds"

# Parameter sets
XS_LIST=(4 8 16 32)
IN_BITS_LIST=(16 8)
W_BITS_LIST=(8 4 2)
# CIN_COUT_LIST=("32 32" "64 64" "128 128" "256 256")
CIN_COUT_LIST=("288 288" "768 288" "32000 288")


# Ensure output directory exists
mkdir -p $OUT_DIR

# Loop over XS values
for XS in "${XS_LIST[@]}"; do
  XS_DIR="${OUT_DIR}/XS${XS}"
  mkdir -p "$XS_DIR"

  echo "=== Building for XS=$XS ==="

  for IN_BITS in "${IN_BITS_LIST[@]}"; do
    for W_BITS in "${W_BITS_LIST[@]}"; do

      # Create subfolder for this (XS, IN_BITS, W_BITS)
      COMBO_DIR="${XS_DIR}/IN${IN_BITS}_W${W_BITS}"
      mkdir -p "$COMBO_DIR"

      echo "  -> IN_BITS=$IN_BITS, W_BITS=$W_BITS"

      for CIN_COUT in "${CIN_COUT_LIST[@]}"; do
        set -- $CIN_COUT
        CIN_MAX=$1
        COUT_MAX=$2

        # Output binary name
        BIN_NAME="Linear_XS${XS}_IN${IN_BITS}_W${W_BITS}_Cin${CIN_MAX}_Cout${COUT_MAX}.riscv"

        echo "     -> Compiling Cin=${CIN_MAX}, Cout=${COUT_MAX} -> $BIN_NAME"

        # # Baremetal build
        # riscv64-unknown-elf-gcc \
        #   -static -specs=htif_nano.specs \
        #   -DXS=${XS} \
        #   -DIN_BITS=${IN_BITS} -DW_BITS=${W_BITS} \
        #   -DCIN_MAX=${CIN_MAX} -DCOUT_MAX=${COUT_MAX} \
        #   -o "${COMBO_DIR}/${BIN_NAME}" "${SRC}"

        # Linux build
        riscv64-unknown-linux-gnu-gcc \
          -static \
          -DXS=${XS} \
          -DIN_BITS=${IN_BITS} -DW_BITS=${W_BITS} \
          -DCIN_MAX=${CIN_MAX} -DCOUT_MAX=${COUT_MAX} \
          -o "${COMBO_DIR}/${BIN_NAME}" "${SRC}"

      done
    done
  done
done

echo "✅ All builds completed. Check the '$OUT_DIR' folder."
