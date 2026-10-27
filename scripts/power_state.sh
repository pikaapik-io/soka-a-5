# Sourced by the real-world grid scripts. Per-core speed on the laptop follows the Windows power mode
# and AC/battery state; both changed mid-session in earlier runs and shifted makespans by up to 50%.
# A desktop PC has no battery and always counts as AC power.
#
# ============================================================================================
# power_state.sh - cek kondisi daya Windows sebelum/selama grid real-world
# ============================================================================================
# Peran:
#   File ini BUKAN skrip yang dijalankan sendiri (tidak ada shebang), tetapi "library" bash yang
#   di-source oleh realworld/run_grid.sh:   source "$REPO/scripts/power_state.sh"
#   Setelah di-source, dua fungsi tersedia: power_state dan require_stable_power.
#
# Kenapa perlu:
#   Eksperimen real-world mengukur waktu eksekusi NYATA di 20 container Docker yang berjalan di
#   laptop (WSL2 di atas Windows). Kecepatan CPU laptop berubah mengikuti power mode Windows
#   (Better battery / Balanced / Best performance) dan status charger (AC vs baterai). Pada sesi
#   sebelumnya mode ini berubah di tengah sesi dan membuat makespan bergeser sampai ~50%, padahal
#   mapping KPB-nya sama. Supaya hasil antar-run bisa dibandingkan, kondisi daya harus:
#     1) dicek sebelum grid dimulai (require_stable_power), dan
#     2) dicatat di awal, setiap run, dan akhir sesi (power_state) untuk bukti/analisis.
#
# Cara kerja:
#   Dari dalam WSL, program Windows bisa dipanggil langsung (powershell.exe, powercfg.exe). Bila
#   powershell.exe tidak ada (Linux biasa / bukan WSL), pengecekan dilewati ("n/a").
#
# Output power_state (satu baris), contoh:
#   power_mode=Best performance plan=Balanced battery_status/charge=2/100% (status 2 = AC)
# ============================================================================================

# power_state: cetak power mode, power plan, dan status baterai/AC Windows dalam satu baris.
power_state() { # Windows power mode, plan and AC status, when running under WSL
  # Bukan WSL (powershell.exe tidak ditemukan di PATH) -> tidak bisa dicek, cetak "n/a" lalu keluar dari fungsi.
  command -v powershell.exe >/dev/null || { echo "n/a"; return; }
  local overlay plan battery
  # Power mode (slider "Power mode" di Settings Windows) disimpan di registry sebagai GUID "overlay"
  # untuk kondisi AC: ActiveOverlayAcPowerScheme. tr -d '\r' membuang carriage return dari output Windows (CRLF).
  overlay=$(powershell.exe -NoProfile -Command '(Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes").ActiveOverlayAcPowerScheme' 2>/dev/null | tr -d '\r')
  # Power plan aktif (Control Panel), mis. "Power Scheme GUID: ... (Balanced)". sed mengambil teks
  # di dalam tanda kurung saja -> "Balanced".
  plan=$(powercfg.exe /getactivescheme 2>/dev/null | tr -d '\r' | sed -E 's/.*\((.*)\).*/\1/')
  # Status baterai dari WMI/CIM: "<BatteryStatus>/<persen>%". BatteryStatus 2 = terhubung ke AC (charger).
  battery=$(powershell.exe -NoProfile -Command '$b = Get-CimInstance Win32_Battery; "$($b.BatteryStatus)/$($b.EstimatedChargeRemaining)%"' 2>/dev/null | tr -d '\r')
  [[ $battery == "/%" || -z $battery ]] && battery="none"  # no battery: desktop PC on mains power
  # Terjemahkan GUID overlay menjadi nama power mode yang terbaca manusia.
  # GUID kosong / nol berarti tidak ada overlay = mode "Balanced".
  case $overlay in
    961cc777-2547-4f9d-8174-7d86181b8a7a) overlay="Better battery" ;;
    3af9b8d9-7c97-431d-ad78-34a8bfea439f) overlay="Better performance" ;;
    ded574b5-45a0-4f42-8737-46345c09c238) overlay="Best performance" ;;
    ""|00000000-0000-0000-0000-000000000000) overlay="Balanced" ;;
  esac
  echo "power_mode=$overlay plan=$plan battery_status/charge=$battery (status 2 = AC)"
}

# Exit unless the machine is on AC power (or has no battery) in "Best performance" (override with FORCE=1).
# require_stable_power: gerbang (guard) sebelum grid real-world dimulai. Skrip pemanggil langsung
# berhenti (exit 1) bila kondisi daya tidak stabil, supaya tidak membuang berjam-jam run dengan
# kecepatan CPU yang berbeda-beda.
require_stable_power() {
  local power
  power=$(power_state)
  # Lolos tanpa cek bila bukan WSL (n/a) atau pengguna sengaja memaksa dengan FORCE=1.
  [[ $power == n/a || -n ${FORCE:-} ]] && return 0
  # Gagal bila power mode bukan "Best performance", ATAU bila tidak sedang di AC (status bukan 2)
  # dan juga bukan PC tanpa baterai ("none"). Pola *"..."* = pencocokan substring pada string power.
  if [[ $power != *"power_mode=Best performance"* \
        || ( $power != *"battery_status/charge=2/"* && $power != *"battery_status/charge=none"* ) ]]; then
    echo "[ERROR] $power" >&2
    echo "        Plug in the charger and set Windows power mode to 'Best performance', or run with FORCE=1" >&2
    # exit (bukan return): karena file ini di-source, exit menghentikan seluruh skrip pemanggil.
    exit 1
  fi
}
