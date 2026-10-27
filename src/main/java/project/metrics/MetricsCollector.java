package project.metrics;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmCost;
import project.setup.SimulationSetup;

/**
 * Menghitung metrik desain (bagian 4.1) dari sebuah run CloudSim yang sudah selesai.
 *
 * <p><b>Peran dalam alur program:</b> dipanggil {@code Experiment} (dan
 * {@code EvaluatorCheck}) tepat setelah {@code SimulationSetup.run(mapping)}.
 * Semua angka dibaca dari objek CloudSim (waktu selesai cloudlet, VM tempat
 * cloudlet berjalan, {@link EnergyMeter}, {@code VmCost}), bukan dari rumus,
 * lalu dikemas menjadi {@link Result}.</p>
 *
 * <p>Delapan metrik yang dihasilkan (n = jumlah task, m = 20 VM,
 * T_j = waktu selesai task terakhir di VM j):</p>
 * <ul>
 *   <li>Makespan: Cmax = max_j T_j.</li>
 *   <li>Energi (kWh): jumlah energi 6 host dari 0 sampai Cmax ({@link EnergyMeter}).</li>
 *   <li>Average response time: rata-rata (waktu selesai - waktu tiba) semua task.</li>
 *   <li>Utilisasi (%): 100 * sum_j T_j / (m * Cmax).</li>
 *   <li>Degree of Imbalance: (T_max - T_min) / T_avg, untuk 20 VM dan untuk VM terpakai saja.</li>
 *   <li>Throughput: n / Cmax (task per detik).</li>
 *   <li>Metrik 7, biaya: CPU + RAM + bandwidth dari {@code VmCost}.</li>
 *   <li>Metrik 8, waktu penjadwalan (ms): diukur pemanggil, hanya diteruskan.</li>
 * </ul>
 */
public final class MetricsCollector {
	// Kelas utilitas (hanya method static), constructor private.
	private MetricsCollector() { }

	/**
	 * Mengumpulkan semua metrik dari simulasi yang sudah dijalankan.
	 *
	 * <p><b>Alur:</b></p>
	 * <ol>
	 *   <li>Satu kali lewat semua cloudlet: cari indeks VM tempat cloudlet
	 *       berjalan, perbarui T_j = waktu selesai terbesar di VM itu, dan
	 *       jumlahkan response time.</li>
	 *   <li>Makespan = T_j terbesar.</li>
	 *   <li>Energi = jumlah {@code hostJoules(makespan)} semua host.</li>
	 *   <li>Biaya = jumlah biaya processing + memory + bandwidth setiap VM.</li>
	 *   <li>Hitung metrik turunan (avg response, utilisasi, DI, throughput) dan
	 *       kemas ke {@link Result}. Pembagian dengan nol dicegah (hasil 0) bila
	 *       tidak ada task atau makespan = 0.</li>
	 * </ol>
	 *
	 * @param scheduler        nama scheduler untuk laporan, misalnya "KPB(k=20.0%)"
	 * @param setup            simulasi yang sudah selesai dijalankan
	 * @param schedulingMillis waktu yang dibutuhkan scheduler membuat mapping (ms, metrik 8)
	 * @return hasil metrik satu run
	 */
	public static Result collect(String scheduler, SimulationSetup setup, double schedulingMillis) {
		List<Cloudlet> cloudlets = setup.cloudlets();
		List<Vm> vms = setup.vmList();

		// T_j: waktu selesai cloudlet terakhir di VM j (semua task tiba pada t = 0).
		// VM yang tidak mendapat task tetap bernilai 0.
		double[] vmFinish = new double[vms.size()];
		double totalResponse = 0;
		for (Cloudlet cloudlet : cloudlets) {
			int vm = vms.indexOf(cloudlet.getVm());
			vmFinish[vm] = Math.max(vmFinish[vm], cloudlet.getFinishTime());
			// Response time = waktu selesai - waktu tiba. Waktu tiba = submission delay (0 di sini),
			// jadi response time mencakup waktu menunggu di antrean VM + waktu eksekusi.
			totalResponse += cloudlet.getFinishTime() - cloudlet.getSubmissionDelay();
		}
		double makespan = Arrays.stream(vmFinish).max().orElse(0);

		// Energi semua host (Joule) dari t = 0 sampai makespan, termasuk daya idle.
		double energyJoules = Arrays.stream(setup.energyMeter().hostJoules(makespan)).sum();

		// Metrik 7: biaya CPU + RAM + bandwidth (biaya storage bukan bagian dari metrik).
		// VmCost CloudSim Plus: processing = (costPerSecond / MIPS per PE host) x total MIPS VM x lama VM aktif;
		// memory = costPerMem x RAM VM (MB); bw = costPerBw x BW VM. Karena VM dibiarkan hidup sampai akhir
		// (vmDestructionDelay di SimulationSetup), VM yang tidak dipakai pun ikut ditagih.
		double cost = 0;
		for (Vm vm : vms) {
			VmCost vmCost = new VmCost(vm);
			cost += vmCost.getProcessingCost() + vmCost.getMemoryCost() + vmCost.getBwCost();
		}

		// Urutan argumen Result: scheduler, n, makespan, energi kWh, avg response,
		// utilisasi % = 100 * sum T_j / (m * Cmax), DI 20 VM, DI VM terpakai (T_j > 0),
		// throughput = n / Cmax, biaya, waktu penjadwalan, T_j per VM.
		return new Result(scheduler, cloudlets.size(), makespan,
				EnergyMeter.toKwh(energyJoules),
				cloudlets.isEmpty() ? 0 : totalResponse / cloudlets.size(),
				makespan == 0 ? 0 : 100 * Arrays.stream(vmFinish).sum() / (vms.size() * makespan),
				degreeOfImbalance(vmFinish),
				degreeOfImbalance(Arrays.stream(vmFinish).filter(t -> t > 0).toArray()),
				makespan == 0 ? 0 : cloudlets.size() / makespan,
				cost, schedulingMillis, vmFinish);
	}

	/**
	 * Degree of Imbalance: (T_max - T_min) / T_avg.
	 *
	 * <p>Makin kecil makin seimbang (0 = semua VM selesai bersamaan). Bila dihitung
	 * atas 20 VM dan sebagian VM tidak dipakai (T = 0, misalnya k = 20% hanya
	 * memakai 10 VM: 8 V3 + 2 V2), T_min = 0 sehingga DI menjadi besar; karena itu
	 * {@link #collect} juga menghitung DI khusus VM terpakai.</p>
	 *
	 * @param times waktu selesai per VM
	 * @return nilai DI; 0 bila array kosong atau rata-ratanya 0
	 */
	static double degreeOfImbalance(double[] times) {
		if (times.length == 0) return 0;
		double avg = Arrays.stream(times).average().orElse(0);
		if (avg == 0) return 0;
		return (Arrays.stream(times).max().getAsDouble() - Arrays.stream(times).min().getAsDouble()) / avg;
	}
}
