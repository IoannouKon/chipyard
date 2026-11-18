#!/bin/bash

# Target directory
LOG_DIR="Log"

# Check if the Log directory exists
if [ ! -d "$LOG_DIR" ]; then
    echo "Error: Directory '$LOG_DIR' not found."
    exit 1
fi

echo "Scanning logs for failures..."
echo "Reporting format: [Index] [Path/To/Subfolder/Filename.log]"
echo "----------------------------------------------------"

# Capture the list of failed files
# We use 'nl' to add the [1], [2] formatting
FAILED_LIST=$(find "$LOG_DIR" -type f \( -name "*.txt" -o -name "*.log" \) -exec grep -l "TEST \[FAIL\]" {} + | nl -s " " -w 2 -n ln | sed 's/^\([0-9]\+\)/[\1]/')

if [ -n "$FAILED_LIST" ]; then
    echo "$FAILED_LIST"
    # Count how many lines were found
    FAILED_COUNT=$(echo "$FAILED_LIST" | wc -l)
else
    FAILED_COUNT=0
fi

echo "----------------------------------------------------"
echo "Scan complete. $FAILED_COUNT tests failed."