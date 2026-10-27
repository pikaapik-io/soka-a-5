package project.scheduler;

import java.util.Arrays;

/**
 * Implementasi algoritma <b>KPB (K-Percent Best)</b> untuk memetakan task (cloudlet)
 * ke VM.
 *
 * <p><b>Posisi dalam alur program:</b> {@link project.Experiment#main} membuat
 * {@code new KpbScheduler(k)} lalu memanggil {@link #schedule(double[], double[])}
 * dengan panjang task dari dataset GoCJ dan kapasitas 20 VM. Hasilnya berupa
 * array {@code mapping} (task i -&gt; VM mapping[i]) yang kemudian dipakai oleh
 * CloudSim (SimulationSetup), model analitik (ScheduleEvaluator), dan runner
 * Docker (realworld/kpb_realworld.py lewat file JSON).</p>
 *
 * <p><b>Ide dasar KPB:</b> KPB adalah kompromi antara dua heuristik klasik:</p>
 * <ul>
 *   <li><b>MET</b> (Minimum Execution Time): selalu pilih VM tercepat untuk task,
 *       tanpa peduli antrean. Cepat per task, tapi beban menumpuk di VM tercepat.</li>
 *   <li><b>MCT</b> (Minimum Completion Time): pilih VM dengan waktu selesai
 *       (ready time + waktu eksekusi) paling kecil di antara <i>semua</i> VM.</li>
 * </ul>
 * <p>KPB pertama-tama menyaring hanya k% VM terbaik (tercepat) untuk task tersebut,
 * lalu menerapkan aturan MCT di dalam subset itu. k = 100% berarti semua VM
 * menjadi kandidat sehingga KPB setara MCT; k yang sangat kecil (s = 1) setara MET.</p>
 *
 * <p><b>Langkah algoritma (lihat juga README bagian 4.3):</b></p>
 * <ol>
 *   <li>Task diproses satu per satu sesuai urutan kedatangan (indeks 0, 1, 2, ...).</li>
 *   <li>Untuk task tersebut, semua VM diurutkan berdasarkan waktu eksekusi
 *       ETC = L / C (panjang task / kapasitas VM), dari yang paling cepat.</li>
 *   <li>Diambil s = ceil(k% x m) VM teratas sebagai kandidat (m = jumlah VM).</li>
 *   <li>Di antara kandidat, dipilih VM dengan completion time terkecil:
 *       CT = readyTime[vm] + L / C[vm].</li>
 *   <li>Assignment di-commit (mapping[task] = vm) dan readyTime VM tersebut
 *       diperbarui menjadi CT, karena VM menjalankan task secara berurutan (FIFO).</li>
 * </ol>
 *
 * <p><b>Kecocokan dengan CloudSim:</b> model "ready time" di atas mengasumsikan
 * setiap VM mengerjakan task satu per satu. Hal ini sesuai dengan
 * CloudletSchedulerSpaceShared di VmFactory, di mana setiap cloudlet memakai
 * seluruh PE VM-nya sehingga cloudlet di VM yang sama antre secara FIFO.</p>
 *
 * <p><b>Kompleksitas:</b> O(N x M log M), N = jumlah task, M = jumlah VM, karena
 * VM di-ranking ulang (sorting) untuk setiap task.</p>
 *
 * <p><b>Catatan (consistent ETC):</b> di project ini ETC = L / C, jadi VM yang lebih
 * cepat selalu lebih cepat untuk semua task. Akibatnya subset k% selalu berisi VM
 * yang sama: k=20% -&gt; 4 VM (V3, indeks 0-3), k=50% -&gt; 10 VM
 * (4 V3 + 6 V2), k=100% -&gt; seluruh 20 VM.</p>
 */
public final class KpbScheduler implements Scheduler {

	/** Nilai k default (persen) bila pengguna tidak memberikan --k: 20% -> 4 dari 20 VM. */
	public static final double DEFAULT_K_PERCENT = 20.0;

	/** Persentase VM terbaik yang dijadikan kandidat, dalam rentang (0, 100]. */
	private final double kPercent;

	/** Membuat scheduler KPB dengan k default ({@link #DEFAULT_K_PERCENT} = 20%). */
	public KpbScheduler() {
		this(DEFAULT_K_PERCENT);
	}

	/**
	 * Membuat scheduler KPB dengan nilai k tertentu.
	 *
	 * @param kPercent persentase VM kandidat; harus angka finite dengan 0 &lt; k &lt;= 100
	 * @throws IllegalArgumentException bila k di luar rentang (0, 100] atau NaN/Infinity
	 */
	public KpbScheduler(double kPercent) {
		// k = 0 tidak bermakna (tidak ada kandidat), k > 100 melebihi jumlah VM yang ada.
		if (!Double.isFinite(kPercent) || kPercent <= 0 || kPercent > 100) {
			throw new IllegalArgumentException("k must be in the range (0, 100]");
		}
		this.kPercent = kPercent;
	}

	/**
	 * Nama scheduler beserta parameter k, misalnya "KPB(k=20.0%)". Dipakai di
	 * stdout, CSV, dan field "scheduler" di JSON mapping.
	 */
	@Override
	public String getName() {
		return "KPB(k=" + kPercent + "%)";
	}

	/** @return nilai k (persen) yang dipakai scheduler ini */
	public double kPercent() {
		return kPercent;
	}

	// ★ TUNJUK: s = ceil(k% x m) - k=20% -> 4 VM, k=50% -> 10 VM, k=100% -> 20 VM (setara MCT).
	/**
	 * Menghitung jumlah VM kandidat (ukuran subset): s = ceil(k% x m), minimal satu.
	 *
	 * <p>Pembulatan ke atas (ceil) memastikan nilai k kecil tetap menghasilkan
	 * minimal satu kandidat. {@code Math.min(vmCount, ...)} menjaga agar s tidak
	 * melebihi jumlah VM, dan {@code Math.max(1, ...)} menjamin s &gt;= 1.</p>
	 *
	 * @param vmCount jumlah VM (m), di project ini 20
	 * @return jumlah VM kandidat s, dengan 1 &lt;= s &lt;= vmCount
	 */
	public int subsetSize(int vmCount) {
		return Math.max(1, Math.min(vmCount, (int) Math.ceil(kPercent / 100.0 * vmCount)));
	}

	/**
	 * Menjalankan algoritma KPB dan menghasilkan mapping task ke VM.
	 *
	 * <p>Alur: validasi input -&gt; hitung ukuran subset s -&gt; inisialisasi
	 * readyTime semua VM = 0 (semua task tiba di t = 0 dan VM masih kosong) -&gt;
	 * untuk setiap task jalankan langkah 1-5 KPB (lihat Javadoc kelas).</p>
	 *
	 * <p>Contoh (data bawaan, 5 task, k = 20%, s = 4, keempat V3 = 10.000 MIPS):
	 * task 900.000/600.000/450.000/300.000/150.000 MI menghasilkan
	 * mapping = [0, 1, 2, 3, 3] dengan makespan analitik 90 s.</p>
	 *
	 * @param taskLength panjang setiap task dalam MI (finite, &gt;= 0)
	 * @param vmCapacity kapasitas setiap VM dalam MIPS (finite, &gt; 0), minimal satu VM
	 * @return mapping[i] = indeks VM untuk task i
	 * @throws IllegalArgumentException bila input tidak valid
	 */
	@Override
	public int[] schedule(double[] taskLength, double[] vmCapacity) {
		validate(taskLength, vmCapacity);

		// s = jumlah kandidat per task; sama untuk semua task karena jumlah VM tetap.
		int subsetSize = subsetSize(vmCapacity.length);
		// readyTime[j] = waktu (detik) VM j selesai mengerjakan semua task yang sudah
		// ditugaskan kepadanya, yaitu kapan VM j siap menerima task berikutnya.
		// Awalnya 0 untuk semua VM (array Java otomatis berisi 0.0).
		double[] readyTime = new double[vmCapacity.length];
		int[] mapping = new int[taskLength.length];

		// ★ TUNJUK: Langkah 1 - task diproses sesuai urutan kedatangan
		for (int task = 0; task < taskLength.length; task++) {
			// ★ TUNJUK: Langkah 2-3 - ranking VM berdasarkan waktu eksekusi (ETC = L/C), ambil subset k% terbaik.
			// candidates berisi SEMUA indeks VM yang sudah terurut dari ETC terkecil;
			// "subset k%" diambil dengan hanya melihat subsetSize elemen pertama di loop bawah.
			Integer[] candidates = rankByExecutionTime(taskLength[task], vmCapacity);

			// ★ TUNJUK: Langkah 4 - di antara subset k%, pilih VM dengan completion time (ready + ETC) terkecil.
			// Nilai awal: kandidat peringkat 1 dan bestFinish = tak hingga, sehingga
			// kandidat pertama di loop pasti terpilih lalu dibandingkan dengan sisanya.
			int selectedVm = candidates[0];
			double bestFinish = Double.POSITIVE_INFINITY;
			for (int c = 0; c < subsetSize; c++) {
				int vm = candidates[c];
				// Completion time CT = ready time VM + ETC task di VM tersebut (L / C).
				double finish = readyTime[vm] + taskLength[task] / vmCapacity[vm];
				// Perbandingan ketat (<): bila CT sama, kandidat dengan peringkat lebih
				// tinggi (lebih cepat, lalu indeks lebih kecil) yang dipertahankan.
				if (finish < bestFinish) {
					bestFinish = finish;
					selectedVm = vm;
				}
			}

			// ★ TUNJUK: Langkah 5 - commit assignment lalu perbarui ready time VM terpilih.
			// VM terpilih kini sibuk sampai bestFinish; task berikutnya di VM ini
			// baru bisa mulai setelah waktu tersebut (antrean sekuensial / FIFO).
			mapping[task] = selectedVm;
			readyTime[selectedVm] = bestFinish;
		}
		return mapping;
	}

	/**
	 * Langkah 2 KPB: mengurutkan indeks VM berdasarkan waktu eksekusi task di VM
	 * tersebut, ETC = length / vmCapacity[vm], dari yang paling kecil (VM tercepat).
	 *
	 * <p>Bila ETC dua VM sama (misalnya dua VM bertipe sama), urutan ditentukan
	 * oleh indeks VM yang lebih kecil, sehingga hasil selalu deterministik
	 * (run berulang menghasilkan mapping yang sama).</p>
	 *
	 * <p>Tipe {@code Integer[]} (bukan {@code int[]}) dipakai karena
	 * {@code Arrays.sort} dengan Comparator hanya menerima array objek.</p>
	 *
	 * @param length     panjang task (MI)
	 * @param vmCapacity kapasitas setiap VM (MIPS)
	 * @return seluruh indeks VM, terurut dari ETC terkecil ke terbesar
	 */
	private static Integer[] rankByExecutionTime(double length, double[] vmCapacity) {
		// Isi awal: 0, 1, 2, ..., m-1 (semua indeks VM).
		Integer[] order = new Integer[vmCapacity.length];
		for (int vm = 0; vm < vmCapacity.length; vm++) {
			order[vm] = vm;
		}
		Arrays.sort(order, (left, right) -> {
			// Kriteria utama: ETC = L / C (lebih kecil = lebih cepat = peringkat lebih tinggi).
			int byExec = Double.compare(length / vmCapacity[left], length / vmCapacity[right]);
			// Kriteria pengganti (tie-breaker): indeks VM lebih kecil didahulukan.
			return byExec != 0 ? byExec : Integer.compare(left, right);
		});
		return order;
	}

	/**
	 * Memeriksa input sebelum penjadwalan agar tidak menghasilkan mapping yang
	 * salah tanpa pesan error.
	 *
	 * <ul>
	 *   <li>Array tidak boleh null dan minimal harus ada satu VM.</li>
	 *   <li>Panjang task harus finite dan &gt;= 0 (task 0 MI diperbolehkan).</li>
	 *   <li>Kapasitas VM harus finite dan &gt; 0 (mencegah pembagian dengan nol pada L / C).</li>
	 * </ul>
	 *
	 * @throws IllegalArgumentException bila salah satu syarat dilanggar
	 */
	private static void validate(double[] taskLength, double[] vmCapacity) {
		if (taskLength == null || vmCapacity == null || vmCapacity.length == 0) {
			throw new IllegalArgumentException("Tasks and at least one VM are required");
		}
		for (double length : taskLength) {
			if (!Double.isFinite(length) || length < 0) {
				throw new IllegalArgumentException("Task lengths must be finite and non-negative");
			}
		}
		for (double capacity : vmCapacity) {
			if (!Double.isFinite(capacity) || capacity <= 0) {
				throw new IllegalArgumentException("VM capacities must be finite and positive");
			}
		}
	}
}
