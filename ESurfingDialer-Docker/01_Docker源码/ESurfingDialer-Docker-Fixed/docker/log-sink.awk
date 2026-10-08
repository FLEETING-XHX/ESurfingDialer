# One persistent logger; byte counts use LC_ALL=C. Helpers run only on rotation/date changes.
function command(action) { return "sh \"" helper "\" " action " " day }
function size(  cmd,n) {
    cmd = command("size")
    cmd | getline n
    close(cmd)
    return n + 0
}
function prepare(newday) {
    if (file != "") close(file)
    day = newday
    file = ENVIRON["LOG_DIR"] "/dialer-" day ".log"
    if (system(command("maintain")) != 0) exit 2
    bytes = size()
}
BEGIN { limit = ENVIRON["LOG_FILE_MAX_BYTES"] + 0; if (limit < 1024) exit 2 }
{
    newday = strftime("%Y-%m-%d")
    if (newday != day) prepare(newday)
    line = $0
    if (length(line) + 1 > limit) line = substr(line, 1, limit - 25) " [log line truncated]"
    length_bytes = length(line) + 1
    if (bytes + length_bytes > limit) {
        close(file)
        if (system(command("rotate")) != 0) exit 2
        bytes = size()
    }
    print line >> file
    bytes += length_bytes
    print line
    fflush()
}
