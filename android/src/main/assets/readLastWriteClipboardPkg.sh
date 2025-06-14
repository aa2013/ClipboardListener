for pkg in $(dumpsys activity activities | grep "packageName=" | awk -F'=' '{print $2}' | awk '{print $1}' | sort -u ); do
    time=$(appops get "$pkg" WRITE_CLIPBOARD | grep allow | awk -F'time=' '{print $2}' | awk '{print $1}')
    [ -n "$time" ] && echo "$pkg: $time"
done | awk -F ': ' '{
    time = $2;
    d=0; h=0; m=0; s=0; ms=0;

    if (match(time, /[0-9]+ms/)) {
        ms_str = substr(time, RSTART, RLENGTH);
        sub(/ms/, "", ms_str);
        ms = ms_str + 0;
        sub(/[0-9]+ms/, "", time);
    }
    if (match(time, /[0-9]+s/)) {
        s_str = substr(time, RSTART, RLENGTH);
        sub(/s/, "", s_str);
        s = s_str + 0;
        sub(/[0-9]+s/, "", time);
    }
    if (match(time, /[0-9]+m/)) {
        m_str = substr(time, RSTART, RLENGTH);
        sub(/m/, "", m_str);
        m = m_str + 0;
        sub(/[0-9]+m/, "", time);
    }
    if (match(time, /[0-9]+h/)) {
        h_str = substr(time, RSTART, RLENGTH);
        sub(/h/, "", h_str);
        h = h_str + 0;
        sub(/[0-9]+h/, "", time);
    }
    if (match(time, /[0-9]+d/)) {
        d_str = substr(time, RSTART, RLENGTH);
        sub(/d/, "", d_str);
        d = d_str + 0;
        sub(/[0-9]+d/, "", time);
    }

    total_ms = d*24 * 60 * 60 * 1000 + h*60 * 60 * 1000 + m*60 * 1000 + s*1000 + ms;
    printf "%s %s %d\n", $1, $2, total_ms;
}' | sort -k3 -n | awk '{print "pkg:"$1",time:"$3}' | head -1