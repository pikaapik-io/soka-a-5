package project.setup;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.vms.Vm;


public final class ConstrainedPlacement {
	// Kelas utilitas (hanya method static), constructor private.
	private ConstrainedPlacement() { }

	
	public static Optional<Host> findHost(VmAllocationPolicy policy, Vm vm) {
		return policy.getHostList().stream()
				.filter(host -> fits(host, vm))
				.min(Comparator.comparingLong(ConstrainedPlacement::usedPes));
	}

	
	
	// di host Tipe B (1.800 MIPS/PE), sehingga keempat V3 wajib berakhir di DC-1.
	static boolean fits(Host host, Vm vm) {
		// Catatan: vm.getMips() dan host.getMips() di CloudSim Plus adalah MIPS per PE, bukan total.
		// host.isSuitableForVm() mengecek RAM (C4) dan BW (C5) lewat provisioner host.
		return usedPes(host) + vm.getNumberOfPes() <= host.getNumberOfPes()   // C3
				&& vm.getMips() <= host.getMips()                             // C6
				&& host.isSuitableForVm(vm);                                  // C4, C5
	}

	/**
	 * Menghitung jumlah PE yang sudah dipakai oleh semua VM di host.
	 *
	 * @param host host yang dihitung
	 * @return total PE dari semua VM yang saat ini berada di host
	 */
	static long usedPes(Host host) {
		return host.getVmList().stream().mapToLong(Vm::getNumberOfPes).sum();
	}

	
	// Rencana deterministik menempatkan VM V3 dan sebagian V2 di DC-1; VM sisanya di DC-2.
	public static Map<Vm, Host> planHosts(List<Datacenter> datacenters, List<Vm> vms) {
		// Identity map: ID host berulang di setiap datacenter,
		// sehingga bila memakai equals() host dari datacenter berbeda bisa dianggap sama dan tergabung.
		// used: pemakaian per host dalam rencana, isi array = {PE, RAM MB, BW Mbps} yang sudah terpakai.
		Map<Host, long[]> used = new IdentityHashMap<>(); // {PEs, RAM, BW}
		Map<Vm, Host> plan = new IdentityHashMap<>();
		for (Vm vm : vms) {
			Host chosen = null;
			for (Datacenter dc : datacenters) {
				// computeIfAbsent: host yang belum pernah dilihat dimulai dengan pemakaian {0, 0, 0}.
				chosen = dc.getHostList().stream()
						.filter(host -> fitsPlanned(host, vm, used.computeIfAbsent(host, h -> new long[3])))
						.min(Comparator.comparingLong(host -> used.get(host)[0]))
						.orElse(null);
				if (chosen != null) {
					break;
				}
			}
			if (chosen == null) {
				throw new IllegalStateException("No host can fit VM " + vm.getId());
			}
			plan.put(vm, chosen);
			// Perbarui pemakaian host terpilih (PE, RAM MB, BW Mbps) untuk VM berikutnya.
			long[] u = used.get(chosen);
			u[0] += vm.getNumberOfPes();
			u[1] += vm.getRam().getCapacity();
			u[2] += vm.getBw().getCapacity();
		}
		return plan;
	}

	/**
	 * Rencana yang sama dengan {@link #planHosts}, tetapi dikelompokkan per
	 * datacenter (bukan per host). Dipakai {@link SimulationSetup} sebagai
	 * "datacenter mapper" broker, sehingga setiap VM langsung dikirim ke
	 * datacenter yang benar.
	 *
	 * @param datacenters daftar datacenter berurutan [DC-1, DC-2]
	 * @param vms         daftar VM berurutan sesuai indeks
	 * @return peta VM -&gt; datacenter tujuan
	 */
	public static Map<Vm, Datacenter> planDatacenters(List<Datacenter> datacenters, List<Vm> vms) {
		Map<Vm, Datacenter> plan = new IdentityHashMap<>();
		planHosts(datacenters, vms).forEach((vm, host) -> plan.put(vm, host.getDatacenter()));
		return plan;
	}

	/**
	 * Versi "rencana" dari {@link #fits}: kapasitas terpakai diambil dari array
	 * {@code used} hasil perencanaan, bukan dari VM yang sudah ada di host
	 * CloudSim (karena pada tahap ini belum ada VM yang benar-benar dibuat).
	 *
	 * @param host host kandidat
	 * @param vm   VM yang akan ditempatkan
	 * @param used pemakaian host saat ini dalam rencana: {PE, RAM MB, BW Mbps}
	 * @return true bila C3, C4, C5 dan C6 semuanya terpenuhi
	 */
	private static boolean fitsPlanned(Host host, Vm vm, long[] used) {
		return used[0] + vm.getNumberOfPes() <= host.getNumberOfPes()                 // C3
				&& used[1] + vm.getRam().getCapacity() <= host.getRam().getCapacity()   // C4
				&& used[2] + vm.getBw().getCapacity() <= host.getBw().getCapacity()     // C5
				&& vm.getMips() <= host.getMips();                                      // C6
	}
}
