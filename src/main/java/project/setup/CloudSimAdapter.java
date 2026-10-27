package project.setup;

import java.util.List;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;

/**
 * Jembatan (adapter) dari objek CloudSim Plus ke array angka sederhana yang
 * dipakai scheduler dan model analitik.
 *
 * <p><b>Peran dalam alur program:</b> scheduler KPB ({@code Scheduler.schedule})
 * sengaja hanya menerima dua array: panjang task (MI) dan kapasitas VM (MIPS).
 * Kelas ini mengubah {@code List<Vm>} / {@code List<Cloudlet>} menjadi array
 * tersebut. {@code Experiment} dan {@code EvaluatorCheck} memanggil
 * {@link #vmCapacities} pada 20 VM dari {@link VmFactory} sebelum menjadwalkan,
 * dan {@code ScheduleContext.fromDesign} memakainya untuk model analitik.
 * Dengan begitu algoritma tidak bergantung pada simulator: mapping yang sama
 * bisa dijalankan di CloudSim maupun di runner real-world (Docker).</p>
 */
public final class CloudSimAdapter {
	// Kelas utilitas (hanya method static), jadi constructor dibuat private agar tidak bisa di-instansiasi.
	private CloudSimAdapter() { }

	/**
	 * Mengambil panjang total setiap cloudlet (MI) sebagai array.
	 *
	 * <p>{@code getTotalLength()} = length per PE x jumlah PE cloudlet, jadi
	 * hasilnya kembali ke panjang task asli dari dataset (lihat
	 * {@code SimulationSetup.createCloudlets} yang membagi panjang per PE).
	 * Saat ini alur utama membaca panjang task langsung dari dataset
	 * ({@code GoCJLoader}), jadi method ini hanya helper pelengkap.</p>
	 *
	 * @param cloudlets daftar cloudlet CloudSim
	 * @return array panjang task; indeks i = cloudlet ke-i dalam list
	 */
	public static double[] taskLengths(List<? extends Cloudlet> cloudlets) {
		return cloudlets.stream().mapToDouble(Cloudlet::getTotalLength).toArray();
	}

	/**
	 * Menghitung kapasitas total setiap VM: C_j = MIPS per PE x jumlah PE.
	 *
	 * <p>Contoh: V3 = 2.500 x 4 = 10.000 MIPS, V2 = 1.500 x 2 = 3.000 MIPS,
	 * V1 = 1.000 x 1 = 1.000 MIPS, sehingga total 20 VM = 72.000 MIPS.
	 * Nilai C_j inilah yang dipakai KPB untuk ETC = L_i / C_j. Karena setiap
	 * cloudlet memakai seluruh PE VM-nya (CloudletSchedulerSpaceShared), waktu
	 * eksekusi di CloudSim juga L_i / C_j, sehingga model scheduler dan simulasi
	 * konsisten.</p>
	 *
	 * @param vms daftar VM (urutan indeks 0..19 dari {@link VmFactory})
	 * @return array kapasitas; indeks j = VM ke-j dalam list
	 */
	public static double[] vmCapacities(List<? extends Vm> vms) {
		return vms.stream().mapToDouble(vm -> vm.getMips() * vm.getNumberOfPes()).toArray();
	}
}
