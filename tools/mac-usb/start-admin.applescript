on run argv
    if (count argv) is not 2 then error "Expected helper path and verified NCM interface"
    do shell script (quoted form of (item 1 of argv) & " " & quoted form of (item 2 of argv)) with administrator privileges
end run
