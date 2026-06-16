#!/bin/bash

PORT=8080

# Find the PID running on the specified port
PID=$(netstat -ano | grep :$PORT | awk '{print $5}' | head -n 1)

# Check if a PID was actually found
if [ -z "$PID" ] || [ "$PID" -eq 0 ]; then
    echo "No process found running on port $PORT."
else
    echo "Found process $PID on port $PORT. Terminating..."
    # Using double slashes //F and //PID so Git Bash passes them correctly to Windows taskkill
    taskkill //F //PID $PID
fi