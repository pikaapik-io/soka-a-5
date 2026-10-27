package project.metrics;

/**
 * Delapan metrik desain bagian 4.1 untuk satu run simulasi (objek nilai yang
 * tidak bisa diubah / immutable).
 *
 * <p><b>Peran dalam alur program:</b> dibuat oleh
 * {@link MetricsCollector#collect}, lalu dibaca {@code Experiment} untuk dicetak
 * ke konsol dan ditulis ke summary.csv, serta oleh {@code EvaluatorCheck} untuk
 * dibandingkan dengan prediksi analitik. Selain 8 metrik, objek ini juga
 * menyimpan nama scheduler, jumlah task, DI khusus VM terpakai, dan waktu
 * selesai setiap VM (T_j).</p>
 */
public final class Result {
	/** Nama scheduler, misalnya "KPB(k=20.0%)". */
	private final String scheduler;
	/** Jumlah task (cloudlet) n. */
	private final int tasks;
	/** Makespan Cmax = max_j T_j (detik). */
	private final double makespan;
	/** Total energi 6 host dari 0 sampai makespan (kWh). */
	private final double energyKwh;
	/** Rata-rata response time semua task (detik). */
	private final double averageResponse;
	/** Utilisasi VM = 100 * sum_j T_j / (m * Cmax) (persen). */
	private final double utilizationPercent;
	/** Degree of Imbalance atas seluruh 20 VM. */
	private final double degreeOfImbalance;
	/** Degree of Imbalance hanya atas VM yang mendapat task (T_j &gt; 0). */
	private final double degreeOfImbalanceUsedVms;
	/** Throughput = n / Cmax (task per detik). */
	private final double throughput;
	/** Biaya total CPU + RAM + bandwidth (USD). */
	private final double totalCost;
	/** Waktu yang dibutuhkan scheduler untuk membuat mapping (ms). */
	private final double schedulingMillis;
	/** T_j: waktu selesai task terakhir di setiap VM (detik); 0 untuk VM tanpa task. */
	private final double[] vmFinishTimes;

	/**
	 * Menyimpan semua nilai metrik. Array {@code vmFinishTimes} disalin
	 * (defensive copy) agar perubahan di luar tidak memengaruhi objek ini.
	 */
	public Result(String scheduler, int tasks, double makespan, double energyKwh,
				  double averageResponse, double utilizationPercent, double degreeOfImbalance,
				  double degreeOfImbalanceUsedVms, double throughput, double totalCost,
				  double schedulingMillis, double[] vmFinishTimes) {
		this.scheduler = scheduler;
		this.tasks = tasks;
		this.makespan = makespan;
		this.energyKwh = energyKwh;
		this.averageResponse = averageResponse;
		this.utilizationPercent = utilizationPercent;
		this.degreeOfImbalance = degreeOfImbalance;
		this.degreeOfImbalanceUsedVms = degreeOfImbalanceUsedVms;
		this.throughput = throughput;
		this.totalCost = totalCost;
		this.schedulingMillis = schedulingMillis;
		this.vmFinishTimes = vmFinishTimes.clone();
	}

	// Getter sederhana; artinya sama dengan komentar field di atas.
	public String scheduler() { return scheduler; }
	public int tasks() { return tasks; }
	public double makespan() { return makespan; }
	public double energyKwh() { return energyKwh; }
	public double averageResponse() { return averageResponse; }
	public double utilizationPercent() { return utilizationPercent; }
	public double degreeOfImbalance() { return degreeOfImbalance; }
	public double degreeOfImbalanceUsedVms() { return degreeOfImbalanceUsedVms; }
	public double throughput() { return throughput; }
	public double totalCost() { return totalCost; }
	public double schedulingMillis() { return schedulingMillis; }
	/** @return salinan T_j per VM (disalin agar isi objek tidak bisa diubah dari luar) */
	public double[] vmFinishTimes() { return vmFinishTimes.clone(); }
}
