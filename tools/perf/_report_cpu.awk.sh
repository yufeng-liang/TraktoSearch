#!/usr/bin/env bash
# 解析 scenario_cpu.sh 采集的原始快照，生成明细报告与 TSV 汇总。
# 用法: _report_cpu.awk.sh <标签> <输出目录> <原始文件>
set -uo pipefail
LABEL="${1:?}"; OUT="${2:?}"; RAW="${3:?}"

awk -v label="$LABEL" -v out="$OUT" '
function stat_cpu(line,  n, arr, rest) {
  n = index(line, ") "); rest = substr(line, n + 2); split(rest, arr, " ")
  return arr[12] + arr[13]
}
function thread_name(line,  a, b) { a = index(line, "("); b = index(line, ") "); return substr(line, a + 1, b - a - 1) }
/^NS=/ { file++; ns[file] = substr($0, 4); sect = ""; next }
/^#GPU/ { gpu_busy = $2; gpu_total = $3; next }
/^#FREQ/ { sect = "FREQ"; pol = $2; next }
/^cpu / { sysbusy[file] = $2+$3+$4+$6+$7+$8; systot[file] = $2+$3+$4+$5+$6+$7+$8; next }
/^#P$/ { sect = "P"; next }
/^#TH$/ { sect = "TH"; next }
sect == "P" { proc[file] = stat_cpu($0); next }
sect == "TH" {
  split($0, f, " "); key = f[1] "|" thread_name($0); c = stat_cpu($0)
  if (file == 1) t1[key] = c; else { t2[key] = c; seen[key] = 1 }
  next
}
sect == "FREQ" && NF == 2 {
  k = file "|" pol
  ftime[k] += $2; fweight[k] += $1 * $2
  if (!(pol in polseen)) { polseen[pol] = 1; npol++; pollist[npol] = pol }
  next
}
END {
  wall_ms = (ns[2] - ns[1]) / 1000000
  app_ms  = (proc[2] - proc[1]) * 10
  sys_ms  = (sysbusy[2] - sysbusy[1]) * 10
  tot_ms  = (systot[2] - systot[1]) * 10
  app_pct = wall_ms > 0 ? app_ms / wall_ms * 100 : 0
  sys_pct = tot_ms > 0 ? sys_ms / tot_ms * 100 : 0
  gpu_pct = (gpu_total > 0) ? gpu_busy / gpu_total * 100 : 0
  gpu_busy_ms = gpu_busy / 1000
  gpu_wall_pct = wall_ms > 0 ? gpu_busy_ms / wall_ms * 100 : 0
  det = out "/cpu_" label ".txt"
  printf "场景: %s\n墙钟: %.0f ms\n应用 CPU: %.0f ms  (%.1f%% 单核当量)\n整机 CPU 占用: %.1f%%\nGPU busy: %.0f ms (占墙钟 %.1f%%, 占 GPU 上电时间 %.1f%%)\n", \
    label, wall_ms, app_ms, app_pct, sys_pct, gpu_busy_ms, gpu_wall_pct, gpu_pct > det
  printf "\nCPU 簇平均频率 (MHz):\n" >> det
  freqline = ""
  for (i = 1; i <= npol; i++) {
    p = pollist[i]
    dt = ftime[2 "|" p] - ftime[1 "|" p]
    dw = fweight[2 "|" p] - fweight[1 "|" p]
    avg = dt > 0 ? dw / dt / 1000 : 0
    printf "  policy%-2s  %.0f MHz\n", p, avg >> det
    freqline = freqline sprintf("%.0f;", avg)
  }
  printf "\n按线程 (Δms >= 10):\n" >> det
  n = 0
  for (k in seen) {
    d = (t2[k] - (k in t1 ? t1[k] : 0)) * 10
    if (d >= 10) { n++; val[n] = d; rows[n] = sprintf("%8.0f ms  %5.1f%%  %s", d, wall_ms > 0 ? d / wall_ms * 100 : 0, k) }
  }
  for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (val[j] > val[i]) { tv=val[i]; val[i]=val[j]; val[j]=tv; ts=rows[i]; rows[i]=rows[j]; rows[j]=ts }
  for (i = 1; i <= n; i++) print rows[i] >> det
  printf "%s\t%.0f\t%.0f\t%.1f\t%.1f\t%.0f\t%.1f\t%s\n", label, wall_ms, app_ms, app_pct, sys_pct, gpu_busy_ms, gpu_wall_pct, freqline >> (out "/cpu_summary.tsv")
  printf "[%s] 应用 CPU %.0f ms / %.0f ms = %.1f%% 单核, 整机 %.1f%%, GPU busy %.0f ms (%.1f%% 墙钟), 簇均频 %s\n", \
    label, app_ms, wall_ms, app_pct, sys_pct, gpu_busy_ms, gpu_wall_pct, freqline
}
' "$RAW"
