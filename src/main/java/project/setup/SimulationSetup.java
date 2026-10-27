package project.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import project.metrics.EnergyMeter;


/**
 * Merakit dan menjalankan satu simulasi CloudSim Plus lengkap untuk satu
 * mapping task -&gt; VM hasil scheduler.
 *
 * <p><b>Peran dalam alur program</b> ({@code Experiment} / {@code EvaluatorCheck}):</p>
 * <ol>
 *   <li>{@code GoCJLoader} membaca panjang task, KPB menghasilkan {@code mapping}.</li>
 *   <li>{@code new SimulationSetup(taskLengths)}: membuat CloudSim, 2 datacenter
 *       (6 host), broker, 20 VM, dan {@link EnergyMeter}.</li>
 *   <li>{@link #run(int[])}: membuat cloudlet sesuai mapping, menempatkan VM ke
 *       datacenter yang sudah direncanakan, mengikat (bind) setiap cloudlet ke VM
 *       pilihan KPB, menjalankan simulasi, lalu memvalidasi hasilnya.</li>
 *   <li>{@code MetricsCollector.collect(...)} membaca objek-objek di kelas ini
 *       (cloudlet, VM, EnergyMeter) untuk menghitung 8 metrik desain.</li>
 * </ol>
 *
 * <p>Satu instance hanya untuk satu kali {@code run}, karena objek CloudSim
 * tidak bisa dijalankan ulang.</p>
 */
public final class SimulationSetup {

	/** Ukuran file (input) cloudlet (KB); ukuran file dan output sama-sama 300, desain bagian 1.3. */
	private static final long FILE_SIZE = 300;
	/** Ukuran output cloudlet (KB), desain bagian 1.3. */
	private static final long OUTPUT_SIZE = 300;
	/**
	 * Fraksi RAM dan BW VM yang dipakai cloudlet (20%). Dipakai sebagai nilai tetap
	 * {@code UtilizationModelDynamic}. CPU tetap 100% ({@code UtilizationModelFull})
	 * karena task GoCJ bersifat compute-bound.
	 */
	private static final double RAM_BW_UTILIZATION = 0.2;

	/** Mesin simulasi (jam dan antrean event). */
	private final CloudSim simulation;
	/** [DC-1 Performance, DC-2 Efficiency] dari {@link DatacenterFactory}. */
	private final List<Datacenter> datacenters;
	/** Broker: perantara yang mengirim VM dan cloudlet ke datacenter atas nama pengguna. */
	private final DatacenterBroker broker;
	/** 20 VM dengan indeks tetap 0..19 (V3, V2, V1), sama dengan indeks yang dipakai mapping. */
	private final List<Vm> vmList;
	/** Panjang total task i dalam MI (salinan, agar tidak berubah dari luar). */
	private final double[] taskLengths;
	/** Cloudlet yang dibuat di {@link #run}; indeks i = task i. */
	private List<Cloudlet> cloudlets = new ArrayList<>();
	/** Semua 6 host dari kedua datacenter, urutan DC-1 lalu DC-2 (dipakai EnergyMeter). */
	private final List<Host> hosts = new ArrayList<>();
	/** Pengukur energi yang mengintegrasikan daya host setiap clock tick. */
	private final EnergyMeter energyMeter;
	/** Salinan mapping yang dijalankan, untuk validasi setelah simulasi. */
	private int[] mappingUsed;

	/**
	 * Membangun seluruh infrastruktur simulasi (belum dijalankan).
	 *
	 * <p>Alur: buat {@code CloudSim} -&gt; buat 2 datacenter dan kumpulkan
	 * 6 host-nya -&gt; buat broker -&gt; buat 20 VM -&gt; daftarkan
	 * {@link EnergyMeter} ke clock tick simulasi. EnergyMeter harus dibuat
	 * sebelum {@code simulation.start()} agar sampel utilisasi tercatat sejak t = 0.</p>
	 *
	 * @param taskLengths panjang setiap task dalam MI (dari dataset GoCJ)
	 */
	public SimulationSetup(double[] taskLengths) {
		simulation = new CloudSim();
		datacenters = DatacenterFactory.createAll(simulation);
		datacenters.forEach(dc -> hosts.addAll(dc.getHostList()));
		broker = new DatacenterBrokerSimple(simulation);
		// Pertahankan setiap VM tetap hidup sampai batch selesai, sehingga VM yang idle pun tetap ditagih.
		// Tanpa delay ini broker boleh menghancurkan VM yang sudah tidak punya cloudlet lebih awal,
		// sehingga lama aktif VM (getTotalExecutionTime, dasar biaya processing di VmCost) menjadi lebih
		// pendek. Dengan Double.MAX_VALUE ke-20 VM dihitung aktif selama seluruh simulasi.
		broker.setVmDestructionDelay(Double.MAX_VALUE);
		vmList = VmFactory.createDefaultVms();
		this.taskLengths = taskLengths.clone();
		energyMeter = new EnergyMeter(simulation, hosts);
	}

	/**
	 * Membuat satu cloudlet untuk setiap task, disesuaikan dengan VM tujuannya.
	 * @param mapping mapping[i] = indeks VM untuk task i
	 * @return daftar cloudlet; ID cloudlet i = task i
	 */
	private List<Cloudlet> createCloudlets(int[] mapping) {
		List<Cloudlet> list = new ArrayList<>();
		for (int i = 0; i < taskLengths.length; i++) {
			int pes = (int) vmList.get(mapping[i]).getNumberOfPes();
			long lengthPerPe = Math.round(taskLengths[i] / pes);
			// UtilizationModelFull: CPU dipakai 100% selama cloudlet berjalan (VM jenuh -> utilisasi host
			// naik sebesar C_j / MIPS_host, inilah yang diukur EnergyMeter).
			// UtilizationModelDynamic(0.2): RAM dan BW dipakai tetap 20% kapasitas VM.
			Cloudlet cloudlet = new CloudletSimple(i, lengthPerPe, pes)
					.setFileSize(FILE_SIZE)
					.setOutputSize(OUTPUT_SIZE)
					.setUtilizationModelCpu(new UtilizationModelFull())
					.setUtilizationModelRam(new UtilizationModelDynamic(RAM_BW_UTILIZATION))
					.setUtilizationModelBw(new UtilizationModelDynamic(RAM_BW_UTILIZATION));
			list.add(cloudlet);
		}
		return list;
	}

	/**
	 * Mengikat cloudlet i ke VM mapping[i], menjalankan simulasi, dan memvalidasi
	 * pembuatan VM.
	 *
	 * <p><b>Alur langkah demi langkah:</b></p>
	 * <ol>
	 *   <li>Cek panjang mapping = jumlah task.</li>
	 *   <li>Buat cloudlet (panjang per PE sesuai VM tujuan) dan simpan salinan mapping.</li>
	 *   <li>Hitung rencana VM -&gt; datacenter dengan
	 *       {@link ConstrainedPlacement#planDatacenters}, lalu pasang sebagai
	 *       "datacenter mapper" broker. Broker CloudSim memanggil fungsi ini untuk
	 *       memilih datacenter setiap VM; argumen {@code lastDc} (datacenter yang
	 *       terakhir dicoba) diabaikan, sehingga VM langsung dikirim ke datacenter
	 *       yang benar tanpa mekanisme retry DC-1 -&gt; DC-2 (lihat catatan bug
	 *       CloudSim Plus 6.6.1 di ConstrainedPlacement).</li>
	 *   <li>Submit 20 VM dan semua cloudlet ke broker. Semua cloudlet tiba
	 *       bersamaan pada t = 0 (offline batch, tanpa submission delay).</li>
	 *   <li>Bind setiap cloudlet ke VM pilihan KPB. Di dalam VM,
	 *       {@code CloudletSchedulerSpaceShared} menjalankannya satu per satu
	 *       (FIFO sesuai urutan submit), cocok dengan model antrean sekuensial /
	 *       ready time KPB.</li>
	 *   <li>{@code simulation.start()} menjalankan simulasi event-driven sampai
	 *       semua event habis.</li>
	 *   <li>{@link #validate()} memastikan hasil simulasi benar-benar sesuai
	 *       rencana.</li>
	 * </ol>
	 *
	 * @param mapping mapping[i] = indeks VM (0..47) untuk task i
	 * @throws IllegalArgumentException bila panjang mapping tidak sama dengan jumlah task
	 * @throws IllegalStateException    bila binding gagal atau validasi gagal
	 */
	public void run(int[] mapping) {
		if (mapping.length != taskLengths.length) {
			throw new IllegalArgumentException("Mapping must contain one VM for every cloudlet");
		}
		cloudlets = createCloudlets(mapping);
		mappingUsed = mapping.clone();
		Map<Vm, Datacenter> plan = ConstrainedPlacement.planDatacenters(datacenters, vmList);
		broker.setDatacenterMapper((lastDc, vm) -> plan.get(vm));
		broker.submitVmList(vmList);
		broker.submitCloudletList(cloudlets);
		// Binding diabaikan diam-diam (mengembalikan false) bila cloudlet belum menjadi milik broker,
		// jadi binding harus dilakukan setelah submit dan hasilnya wajib dicek.
		for (int i = 0; i < mapping.length; i++) {
			if (!broker.bindCloudletToVm(cloudlets.get(i), vmList.get(mapping[i]))) {
				throw new IllegalStateException("Cloudlet " + i + " could not be bound to VM " + mapping[i]);
			}
		}
		simulation.start();
		validate();
	}

	/**
	 * Risiko desain 3: kegagalan alokasi VM di CloudSim tidak memunculkan error
	 * (diam-diam), jadi diperiksa secara eksplisit.
	 *
	 * <p>Tiga pemeriksaan, masing-masing melempar {@code IllegalStateException}
	 * sehingga metrik tidak pernah dicetak dari simulasi yang salah:</p>
	 * <ol>
	 *   <li>Semua 20 VM berhasil dibuat (kalau tidak, kapasitas host atau
	 *       kecocokan MIPS/PE (C6) bermasalah).</li>
	 *   <li>Setiap cloudlet benar-benar berjalan di VM yang dipilih KPB
	 *       (perbandingan identitas objek VM).</li>
	 *   <li>Semua cloudlet berstatus selesai (finished).</li>
	 * </ol>
	 */
	private void validate() {
		int created = broker.getVmCreatedList().size();
		if (created != vmList.size()) {
			throw new IllegalStateException("Only " + created + " of " + vmList.size()
					+ " VMs were created; check host capacity and MIPS compatibility");
		}
		for (int i = 0; i < cloudlets.size(); i++) {
			if (cloudlets.get(i).getVm() != vmList.get(mappingUsed[i])) {
				throw new IllegalStateException("Cloudlet " + i + " ran on a different VM than scheduled");
			}
		}
		long finished = cloudlets.stream().filter(Cloudlet::isFinished).count();
		if (finished != cloudlets.size()) {
			throw new IllegalStateException("Only " + finished + " of " + cloudlets.size()
					+ " cloudlets finished");
		}
	}

	/** @return instance CloudSim simulasi ini */
	public CloudSim simulation() { return simulation; }
	/** @return [DC-1 Performance, DC-2 Efficiency] */
	public List<Datacenter> datacenters() { return datacenters; }
	/** @return 6 host (DC-1, lalu DC-2) */
	public List<Host> hosts() { return hosts; }
	/** @return broker simulasi (berisi daftar VM yang berhasil dibuat, dll.) */
	public DatacenterBroker broker() { return broker; }
	/** @return 20 VM dengan indeks 0..19 */
	public List<Vm> vmList() { return vmList; }
	/** @return cloudlet hasil {@link #run}; kosong sebelum run dipanggil */
	public List<Cloudlet> cloudlets() { return cloudlets; }
	/** @return pengukur energi yang terpasang di simulasi ini */
	public EnergyMeter energyMeter() { return energyMeter; }
}
