package project.scheduler;

/**
 * Antarmuka (interface) umum untuk semua algoritma penjadwalan task ke VM.
 *
 * <p><b>Peran dalam alur program:</b> {@link project.Experiment} membuat sebuah
 * scheduler (saat ini {@link KpbScheduler}), lalu memanggil {@link #schedule}
 * dengan dua array angka saja. Hasilnya (array {@code mapping}) kemudian dipakai
 * oleh {@code SimulationSetup} untuk mengikat (bind) setiap cloudlet ke VM
 * tujuannya di CloudSim Plus, oleh {@code ScheduleEvaluator} untuk prediksi
 * analitik, dan diekspor ke JSON untuk runner real-world (Docker).</p>
 *
 * <p><b>Mengapa hanya data level scheduler?</b> Scheduler sengaja tidak
 * menerima objek CloudSim (Cloudlet, Vm, Host). Ia hanya melihat panjang task
 * (MI) dan kapasitas VM (MIPS). Dengan begitu algoritma terpisah dari simulator:
 * mapping yang sama bisa dijalankan di CloudSim maupun di Docker, dan algoritma
 * lain cukup mengimplementasikan interface ini tanpa menyentuh kode simulasi.</p>
 *
 * (Teks asli: "Assigns tasks to virtual machines using only scheduler-level data.")
 */
public interface Scheduler {

	/**
	 * Nama scheduler untuk ditampilkan di output/CSV/JSON, misalnya "KPB(k=20.0%)".
	 *
	 * @return nama yang mudah dibaca, termasuk parameter penting algoritma
	 */
	String getName();

	/**
	 * Menghitung mapping task ke VM.
	 *
	 * @param taskLength taskLength[i] adalah panjang task i dalam MI (Million Instructions)
	 * @param vmCapacity vmCapacity[j] adalah kapasitas VM j dalam MIPS
	 *                   (total = MIPS per PE x jumlah PE, lihat CloudSimAdapter.vmCapacities)
	 * @return mapping[i] adalah indeks VM yang ditugaskan untuk task i
	 *         (panjang array sama dengan taskLength.length, nilai 0..vmCapacity.length-1)
	 */
	int[] schedule(double[] taskLength, double[] vmCapacity);
}
