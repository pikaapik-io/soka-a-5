package project.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.vms.Vm;
import project.setup.CloudSimAdapter;
import project.setup.ConstrainedPlacement;
import project.setup.DatacenterFactory;
import project.setup.VmFactory;

/**
 * Semua data yang dibutuhkan {@link ScheduleEvaluator} selain taskLength/vmCapacity:
 * di host mana setiap VM berada, serta daya idle/max dan total MIPS setiap host
 * (desain bagian 3.2).
 *
 * <p><b>Peran dalam alur program:</b> scheduler (KPB) hanya melihat panjang task
 * dan kapasitas VM. Untuk memprediksi <i>energi</i> secara analitik, kita juga
 * perlu tahu infrastruktur fisiknya: VM j ditempatkan di host mana, dan berapa
 * P_idle, P_max, serta total MIPS host tersebut. Kelas ini membungkus semua data
 * itu dalam bentuk array sederhana sehingga ScheduleEvaluator bisa menghitung
 * makespan dan energi tanpa menjalankan simulasi CloudSim.</p>
 *
 * <p>Di {@link project.Experiment} kelas ini dipakai dengan pola
 * {@code ScheduleContext.fromDesign(new double[0]).withTasks(taskLengths, vmCapacities)}:
 * infrastruktur dibangun dari desain, lalu diisi dengan task dari dataset.</p>
 *
 * <p>Kelas ini <b>immutable</b>: setiap array di-clone saat masuk (constructor)
 * dan saat keluar (getter), jadi data internal tidak bisa diubah dari luar.</p>
 *
 * <p>Konvensi indeks: VM memakai urutan VmFactory (0-3 V3, 4-11 V2, 12-19 V1);
 * host memakai urutan gabungan host DC-1 lalu DC-2.</p>
 */
public final class ScheduleContext {
	/** taskLength[i] = panjang task i (MI). */
	private final double[] taskLength;
	/** vmCapacity[j] = kapasitas VM j (MIPS x PE). */
	private final double[] vmCapacity;
	/** vmHost[j] = indeks host tempat VM j ditempatkan. */
	private final int[] vmHost;
	/** hostIdlePower[h] = daya host h saat utilisasi 0% (Watt). */
	private final double[] hostIdlePower;
	/** hostMaxPower[h] = daya host h saat utilisasi 100% (Watt). */
	private final double[] hostMaxPower;
	/** hostTotalMips[h] = total MIPS host h (jumlah PE x MIPS per PE). */
	private final double[] hostTotalMips;

	/**
	 * Membuat context dari array mentah, dengan validasi konsistensi ukuran dan indeks.
	 *
	 * @param taskLength    panjang setiap task (MI)
	 * @param vmCapacity    kapasitas setiap VM (MIPS); panjangnya harus sama dengan vmHost
	 * @param vmHost        indeks host untuk setiap VM; setiap nilai harus 0..jumlah host-1
	 * @param hostIdlePower P_idle setiap host (W)
	 * @param hostMaxPower  P_max setiap host (W)
	 * @param hostTotalMips total MIPS setiap host
	 * @throws IllegalArgumentException bila ada argumen null, ukuran array tidak cocok,
	 *                                  atau vmHost menunjuk host yang tidak ada
	 */
	public ScheduleContext(double[] taskLength, double[] vmCapacity, int[] vmHost,
							double[] hostIdlePower, double[] hostMaxPower, double[] hostTotalMips) {
		if (taskLength == null || vmCapacity == null || vmHost == null
				|| hostIdlePower == null || hostMaxPower == null || hostTotalMips == null) {
			throw new IllegalArgumentException("All arguments are required");
		}
		// Setiap VM harus punya tepat satu host.
		if (vmCapacity.length == 0 || vmCapacity.length != vmHost.length) {
			throw new IllegalArgumentException("vmCapacity and vmHost must have the same, non-zero length");
		}
		// Ketiga array host harus berukuran sama (satu entri per host).
		if (hostIdlePower.length == 0 || hostIdlePower.length != hostMaxPower.length
				|| hostIdlePower.length != hostTotalMips.length) {
			throw new IllegalArgumentException("Host arrays must have the same, non-zero length");
		}
		// Indeks host di vmHost harus valid agar tidak terjadi ArrayIndexOutOfBounds saat evaluasi.
		for (int host : vmHost) {
			if (host < 0 || host >= hostIdlePower.length) {
				throw new IllegalArgumentException("vmHost contains an invalid host index");
			}
		}
		// Salinan defensif (clone) supaya perubahan array milik pemanggil tidak memengaruhi context.
		this.taskLength = taskLength.clone();
		this.vmCapacity = vmCapacity.clone();
		this.vmHost = vmHost.clone();
		this.hostIdlePower = hostIdlePower.clone();
		this.hostMaxPower = hostMaxPower.clone();
		this.hostTotalMips = hostTotalMips.clone();
	}

	/**
	 * Membangun context dari desain project (VmFactory/DatacenterFactory), memakai
	 * {@link ConstrainedPlacement#planHosts} untuk penempatan VM ke host -- penempatan
	 * deterministik yang sama dengan hasil VmAllocationPolicySimple CloudSim saat
	 * runtime (sudah diverifikasi terhadap results/simulation/placement.csv).
	 *
	 * <p>Alur:</p>
	 * <ol>
	 *   <li>Buat objek CloudSim sementara (tidak pernah di-start) hanya untuk bisa
	 *       membuat objek Datacenter/Host/Vm sesuai desain.</li>
	 *   <li>Kumpulkan host dari DC-1 dan DC-2 ke satu list (total 6 host); posisi
	 *       di list ini menjadi indeks host.</li>
	 *   <li>Buat 20 VM default dan rencanakan host untuk tiap VM dengan aturan C3-C6.</li>
	 *   <li>Ubah semuanya menjadi array: kapasitas VM, indeks host per VM, dan
	 *       P_idle/P_max/total MIPS per host berdasarkan tipe host (A, B atau C).</li>
	 * </ol>
	 *
	 * @param taskLength panjang task (MI); boleh array kosong bila task akan diisi
	 *                   belakangan lewat {@link #withTasks}
	 * @return context berisi infrastruktur desain dan task yang diberikan
	 */
	public static ScheduleContext fromDesign(double[] taskLength) {
		CloudSim simulation = new CloudSim();
		List<Datacenter> datacenters = DatacenterFactory.createAll(simulation);
		// Gabungkan host semua datacenter; indeks list ini = indeks host di vmHost.
		List<Host> hosts = new ArrayList<>();
		datacenters.forEach(dc -> hosts.addAll(dc.getHostList()));
		List<Vm> vms = VmFactory.createDefaultVms();
		// Rencana penempatan VM -> host (aturan sama dengan yang dipakai CloudSim saat simulasi).
		Map<Vm, Host> placement = ConstrainedPlacement.planHosts(datacenters, vms);

		// Kapasitas VM = MIPS per PE x jumlah PE (sama persis dengan yang dilihat scheduler).
		double[] vmCapacity = CloudSimAdapter.vmCapacities(vms);
		int[] vmHost = new int[vms.size()];
		for (int i = 0; i < vms.size(); i++) {
			// indexOf memakai referensi objek host yang sama, jadi menghasilkan posisi host di list gabungan.
			vmHost[i] = hosts.indexOf(placement.get(vms.get(i)));
		}

		// Parameter daya dan MIPS setiap host diambil dari tipe host-nya (Tipe A, B atau C).
		double[] idlePower = new double[hosts.size()];
		double[] maxPower = new double[hosts.size()];
		double[] totalMips = new double[hosts.size()];
		for (int h = 0; h < hosts.size(); h++) {
			DatacenterFactory.HostType type = DatacenterFactory.typeOf(hosts.get(h));
			idlePower[h] = type.idlePower;
			maxPower[h] = type.maxPower;
			totalMips[h] = type.totalMips();
		}
		return new ScheduleContext(taskLength, vmCapacity, vmHost, idlePower, maxPower, totalMips);
	}

	/**
	 * Host dan penempatan VM yang sama, tetapi dengan task/kapasitas berbeda (yaitu
	 * data yang diterima Scheduler.schedule()).
	 *
	 * @param newTaskLength panjang task baru (MI)
	 * @param newVmCapacity kapasitas VM baru (MIPS); jumlahnya harus tetap sama dengan jumlah VM
	 * @return context baru; context lama tidak berubah
	 */
	public ScheduleContext withTasks(double[] newTaskLength, double[] newVmCapacity) {
		return new ScheduleContext(newTaskLength, newVmCapacity, vmHost, hostIdlePower, hostMaxPower, hostTotalMips);
	}

	// Getter di bawah selalu mengembalikan salinan (clone) agar context tetap immutable.

	/** @return salinan panjang task (MI) */
	public double[] taskLength() { return taskLength.clone(); }
	/** @return salinan kapasitas VM (MIPS) */
	public double[] vmCapacity() { return vmCapacity.clone(); }
	/** @return salinan indeks host untuk setiap VM */
	public int[] vmHost() { return vmHost.clone(); }
	/** @return salinan P_idle setiap host (W) */
	public double[] hostIdlePower() { return hostIdlePower.clone(); }
	/** @return salinan P_max setiap host (W) */
	public double[] hostMaxPower() { return hostMaxPower.clone(); }
	/** @return salinan total MIPS setiap host */
	public double[] hostTotalMips() { return hostTotalMips.clone(); }
	/** @return jumlah task */
	public int taskCount() { return taskLength.length; }
	/** @return jumlah VM */
	public int vmCount() { return vmCapacity.length; }
	/** @return jumlah host */
	public int hostCount() { return hostIdlePower.length; }
}
