package project.setup;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.power.models.PowerModelHostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;


public final class DatacenterFactory {

	/**
	 * Tipe host: jumlah PE, MIPS per PE, RAM (MB), BW (Mbps), storage (MB), daya (W).
	 * Perhatikan urutan parameter constructor: daya maksimum dulu, baru daya idle.
	 */
	public enum HostType {
		// Host Tipe A (DC-1 Performance), 8 PE x 3.000 MIPS, 32 GB RAM, 10 Gbps.
		A_PERFORMANCE(8, 3_000, 32_768, 10_000, 1_000_000, 250, 175),
		// Host Tipe B (DC-2 Efficiency), 8 PE x 1.800 MIPS, 32 GB RAM, 5 Gbps.
		B_EFFICIENCY(8, 1_800, 32_768, 5_000, 1_000_000, 120, 72);

		/** Jumlah PE (core) fisik host. */
		public final int pes;
		/** MIPS setiap PE. */
		public final double mipsPerPe;
		/** RAM host dalam MB (32.768 MB = 32 GB, 65.536 MB = 64 GB). */
		public final long ram;
		/** Bandwidth host dalam Mbps. */
		public final long bw;
		/** Storage host dalam MB. */
		public final long storage;
		/** Daya saat utilisasi CPU 100% (P_max), dalam Watt. */
		public final double maxPower;
		/** Daya saat host menyala tetapi tidak bekerja (P_idle), dalam Watt. */
		public final double idlePower;

		/** Constructor enum; urutan argumen sama dengan deklarasi konstanta di atas. */
		HostType(int pes, double mipsPerPe, long ram, long bw, long storage,
				 double maxPower, double idlePower) {
			this.pes = pes;
			this.mipsPerPe = mipsPerPe;
			this.ram = ram;
			this.bw = bw;
			this.storage = storage;
			this.maxPower = maxPower;
			this.idlePower = idlePower;
		}

		/**
		 * Total MIPS host = PE x MIPS per PE (Tipe A = 24.000, Tipe B = 14.400).
		 * Dipakai model analitik sebagai pembagi utilisasi: u = C_j / MIPS_host.
		 */
		public double totalMips() { return pes * mipsPerPe; }
	}

	/**
	 * Scheduling interval datacenter (detik simulasi). Dengan nilai 1.0,
	 * datacenter memperbarui proses cloudlet secara berkala setiap 1 detik
	 * selama masih ada cloudlet berjalan, sehingga jam simulasi "berdetak"
	 * (clock tick) cukup rapat. Setiap tick dipakai {@code EnergyMeter} untuk
	 * mengambil sampel utilisasi host, jadi interval ini juga menentukan resolusi
	 * integrasi energi.
	 */
	public static final double SCHEDULING_INTERVAL = 1.0;

	// 3 host per DC -> total 2 DC x 3 host = 6 host (48 PE, 192 GB RAM di seluruh sistem).
	private static final int HOSTS_PER_DATACENTER = 3;
	// Konversi GB -> MB untuk biaya RAM/storage (CloudSim menghitung biaya per MB).
	private static final double MB_PER_GB = 1024.0;

	// Kelas utilitas (hanya method static), constructor private.
	private DatacenterFactory() { }

	// Dua datacenter dibuat: DC-1 Performance (Tipe A), DC-2 Efficiency (Tipe B).
	/**
	 * Membuat kedua datacenter. Urutan list penting: {@link ConstrainedPlacement#planHosts}
	 * memeriksa DC-1 lebih dulu, sehingga VM besar mengisi host tercepat dan VM kecil
	 * yang tidak muat berakhir di DC-2 yang lebih hemat.
	 *
	 * <p>DC-1: $0,05/detik, $0,02/GB RAM, $0,001/GB storage, $0,005/Mbps BW.
	 * DC-2: $0,03/detik, $0,01/GB RAM, $0,0008/GB storage, $0,003/Mbps BW.</p>
	 *
	 * @param simulation instance CloudSim tempat datacenter didaftarkan
	 * @return [DC-1 Performance, DC-2 Efficiency]
	 */
	public static List<Datacenter> createAll(CloudSim simulation) {
		List<Datacenter> datacenters = new ArrayList<>();
		// Argumen: tipe host, cost per detik, cost per GB RAM, cost per GB storage, cost per Mbps BW.
		datacenters.add(create(simulation, HostType.A_PERFORMANCE, 0.05, 0.02, 0.001, 0.005));
		datacenters.add(create(simulation, HostType.B_EFFICIENCY, 0.03, 0.01, 0.0008, 0.003));
		return datacenters;
	}

	/**
	 * Membuat satu datacenter berisi {@link #HOSTS_PER_DATACENTER} host bertipe sama beserta karakteristik
	 * biayanya.
	 *
	 * <p>Biaya mengikuti tabel desain: per detik, per GB RAM, per GB storage, per
	 * Mbps bandwidth. CloudSim mengharapkan biaya RAM dan storage per MB, jadi
	 * nilai per GB dibagi 1024.</p>
	 *
	 * <p>Cara CloudSim Plus (kelas {@code VmCost}) memakai angka ini, yang
	 * kemudian dijumlahkan {@code MetricsCollector}:</p>
	 * <ul>
	 *   <li>processing = (costPerSecond / MIPS per PE host) x total MIPS VM x lama VM aktif (detik),</li>
	 *   <li>memory = costPerMem x RAM VM (MB),</li>
	 *   <li>bw = costPerBw x BW VM (Mbps).</li>
	 * </ul>
	 *
	 * <p>Alur: buat 3 host -&gt; buat {@code DatacenterSimple} dengan
	 * {@code VmAllocationPolicySimple} yang memakai
	 * {@link ConstrainedPlacement#findHost} sebagai fungsi pemilih host (agar
	 * C3-C6 ditegakkan) -&gt; set scheduling interval -&gt; isi karakteristik.</p>
	 *
	 * @param simulation       instance CloudSim
	 * @param type             tipe host untuk semua host di datacenter ini
	 * @param costPerSecond    biaya pemrosesan per detik ($)
	 * @param costPerGbRam     biaya per GB RAM ($)
	 * @param costPerGbStorage biaya per GB storage ($)
	 * @param costPerBw        biaya per Mbps bandwidth ($)
	 * @return datacenter yang sudah terdaftar di simulasi
	 */
	private static Datacenter create(CloudSim simulation, HostType type, double costPerSecond,
									 double costPerGbRam, double costPerGbStorage, double costPerBw) {
		List<Host> hosts = new ArrayList<>();
		for (int i = 0; i < HOSTS_PER_DATACENTER; i++) {
			hosts.add(createHost(type));
		}
		// Policy "Simple" dengan fungsi findHost kustom: CloudSim memanggil ConstrainedPlacement::findHost
		// untuk setiap VM yang akan dibuat di datacenter ini.
		DatacenterSimple datacenter = new DatacenterSimple(simulation, hosts,
				new VmAllocationPolicySimple(ConstrainedPlacement::findHost));
		datacenter.setSchedulingInterval(SCHEDULING_INTERVAL);
		// Arsitektur/OS/VMM hanya metadata deskriptif (dicetak di log), tidak memengaruhi perhitungan.
		datacenter.getCharacteristics()
				.setArchitecture("x86")
				.setOs("Linux")
				.setVmm("Xen")
				.setCostPerSecond(costPerSecond)
				.setCostPerMem(costPerGbRam / MB_PER_GB)
				.setCostPerStorage(costPerGbStorage / MB_PER_GB)
				.setCostPerBw(costPerBw);
		return datacenter;
	}

	/**
	 * Membuat satu host fisik: daftar PE, RAM, BW, storage, VM scheduler, dan
	 * power model.
	 *
	 * @param type tipe host (A atau B)
	 * @return host CloudSim yang siap dimasukkan ke datacenter
	 */
	private static Host createHost(HostType type) {
		// Satu objek PeSimple per core fisik, masing-masing dengan MIPS per PE tipe host.
		List<Pe> pes = new ArrayList<>();
		for (int pe = 0; pe < type.pes; pe++) {
			pes.add(new PeSimple(type.mipsPerPe));
		}
		HostSimple host = new HostSimple(type.ram, type.bw, type.storage, pes);
		// Time-shared di level host: VM-VM berbagi PE host. Kelebihan PE (overcommit) dicegah
		// oleh ConstrainedPlacement (C3), bukan oleh scheduler ini.
		host.setVmScheduler(new VmSchedulerTimeShared());
		// Model linear P(u) = P_idle + (P_max - P_idle) * u
		// (argumen PowerModelHostSimple: daya maksimum, lalu daya statis/idle).
		// Model ini hanya memberi daya sesaat; energi (integral terhadap waktu) dihitung EnergyMeter.
		host.setPowerModel(new PowerModelHostSimple(type.maxPower, type.idlePower));
		return host;
	}

	/**
	 * Mengenali tipe host dari MIPS per PE-nya: 3.000 MIPS/PE = Tipe A,
	 * 1.800 = Tipe B. Karena itu setiap tipe host wajib punya
	 * MIPS/PE yang berbeda. Dipakai {@code EnergyMeter}, {@code ScheduleContext}
	 * dan {@code Experiment} untuk mengambil daya idle/max dan nama datacenter
	 * dari sebuah objek Host.
	 *
	 * @param host host CloudSim
	 * @return tipe host yang sesuai
	 * @throws IllegalArgumentException bila MIPS/PE host tidak cocok dengan tipe mana pun
	 */
	public static HostType typeOf(Host host) {
		for (HostType type : HostType.values()) {
			if (host.getMips() == type.mipsPerPe) {
				return type;
			}
		}
		throw new IllegalArgumentException("Tipe host tidak dikenal: " + host.getMips() + " MIPS/PE");
	}
}
