# Sourced by the real-world grid scripts. Per-core speed on the laptop follows the Windows power mode
# and AC/battery state; both changed mid-session in earlier runs and shifted makespans by up to 50%.
# A desktop PC has no battery and always counts as AC power.

power_state() { # Windows power mode, plan and AC status, when running under WSL
  command -v powershell.exe >/dev/null || { echo "n/a"; return; }
  local overlay plan battery
  overlay=$(powershell.exe -NoProfile -Command '(Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes").ActiveOverlayAcPowerScheme' 2>/dev/null | tr -d '\r')
  plan=$(powercfg.exe /getactivescheme 2>/dev/null | tr -d '\r' | sed -E 's/.*\((.*)\).*/\1/')
  battery=$(powershell.exe -NoProfile -Command '$b = Get-CimInstance Win32_Battery; "$($b.BatteryStatus)/$($b.EstimatedChargeRemaining)%"' 2>/dev/null | tr -d '\r')
  [[ $battery == "/%" || -z $battery ]] && battery="none"  # no battery: desktop PC on mains power
  case $overlay in
    961cc777-2547-4f9d-8174-7d86181b8a7a) overlay="Better battery" ;;
    3af9b8d9-7c97-431d-ad78-34a8bfea439f) overlay="Better performance" ;;
    ded574b5-45a0-4f42-8737-46345c09c238) overlay="Best performance" ;;
    ""|00000000-0000-0000-0000-000000000000) overlay="Balanced" ;;
  esac
  echo "power_mode=$overlay plan=$plan battery_status/charge=$battery (status 2 = AC)"
}

# Exit unless the machine is on AC power (or has no battery) in "Best performance" (override with FORCE=1).
require_stable_power() {
  local power
  power=$(power_state)
  [[ $power == n/a || -n ${FORCE:-} ]] && return 0
  if [[ $power != *"power_mode=Best performance"* \
        || ( $power != *"battery_status/charge=2/"* && $power != *"battery_status/charge=none"* ) ]]; then
    echo "[ERROR] $power" >&2
    echo "        Plug in the charger and set Windows power mode to 'Best performance', or run with FORCE=1" >&2
    exit 1
  fi
}
