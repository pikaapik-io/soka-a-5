package project.metrics;

import java.util.List;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.hosts.Host;
import project.setup.DatacenterFactory;
import project.setup.DatacenterFactory.HostType;

/**
 * Mengintegrasikan daya host terhadap waktu simulasi (desain bagian 3.2):
 * E = sum_k integral [P_idle + (P_max - P_idle) * u_k(t)] dt, t dari 0 sampai Cmax.
 *
 * <p>{@code PowerModelHostSimple} hanya memberi daya sesaat (Watt), bukan energi
 * (Joule), jadi utilisasi diambil sampelnya pada setiap clock tick lalu
 * diintegrasikan di kelas ini (risiko desain 2). Setiap host dihitung selama
 * seluruh run, termasuk host yang idle.</p>
 *
 * <p>Karena P_idle dan P_max konstan per host, integral di atas bisa dipecah:
 * E_k = P_idle_k * Cmax + (P_max_k - P_idle_k) * integral u_k(t) dt.
 * Kelas ini cukup mengakumulasi integral u_k(t) dt (satuan "detik pada utilisasi
 * 100%") selama simulasi, lalu {@link #hostJoules(double)} menghitung energinya
 * setelah makespan diketahui.</p>
 *
 * <p><b>Peran dalam alur program:</b> dibuat oleh {@code SimulationSetup}
 * sebelum simulasi dimulai (mendaftarkan listener clock tick), lalu
 * {@code MetricsCollector} memanggil {@link #hostJoules(double)} setelah
 * simulasi selesai untuk metrik energi. Rumus yang sama dipakai secara analitik
 * oleh {@code ScheduleEvaluator}, sehingga keduanya bisa dibandingkan
 * ({@code EvaluatorCheck}).</p>
 */
public final class EnergyMeter {
	/** Host yang diukur (6 host, urutan DC-1 lalu DC-2). */
	private final List<Host> hosts;
	/** Energi per host (Joule), diisi oleh {@link #hostJoules(double)}. */
	private final double[] joules;
	/** Akumulasi integral u_k(t) dt per host (detik pada utilisasi 100%). */
	private final double[] utilizationSeconds;
	/** Utilisasi CPU host (0..1) pada tick terakhir, dipakai untuk interval berikutnya. */
	private final double[] lastUtilization;
	/** Waktu simulasi tick terakhir (detik). */
	private double lastTime;

	/**
	 * Menyiapkan array per host dan mendaftarkan {@link #sample(double)} sebagai
	 * listener yang dipanggil CloudSim setiap kali jam simulasi maju (clock tick).
	 *
	 * @param simulation instance CloudSim yang akan diukur
	 * @param hosts      semua host yang dihitung energinya
	 */
	public EnergyMeter(CloudSim simulation, List<Host> hosts) {
		this.hosts = hosts;
		this.joules = new double[hosts.size()];
		this.utilizationSeconds = new double[hosts.size()];
		this.lastUtilization = new double[hosts.size()];
		simulation.addOnClockTickListener(info -> sample(info.getTime()));
	}

	/**
	 * Satu langkah integrasi numerik, dipanggil pada setiap clock tick.
	 *
	 * <p>Metode persegi panjang (zero-order hold): utilisasi yang dibaca pada tick
	 * sebelumnya dianggap konstan sampai tick sekarang, sehingga
	 * integral u_k dt += u_k(t_sebelum) * (t_sekarang - t_sebelum). Pendekatan ini
	 * cocok untuk simulasi event-driven, karena utilisasi hanya berubah saat ada
	 * event (cloudlet mulai/selesai), dan scheduling interval datacenter 1 detik
	 * ({@code DatacenterFactory.SCHEDULING_INTERVAL}) membuat tick cukup rapat.
	 * Selisih kecil terhadap model analitik diperiksa oleh {@code EvaluatorCheck}.</p>
	 *
	 * <p>Alur: hitung dt -&gt; untuk setiap host tambahkan kontribusi interval yang
	 * baru lewat (bila dt &gt; 0) -&gt; baca utilisasi host saat ini untuk interval
	 * berikutnya -&gt; simpan waktu tick.</p>
	 *
	 * @param now waktu simulasi saat tick ini (detik)
	 */
	private void sample(double now) {
		double dt = now - lastTime;
		for (int k = 0; k < hosts.size(); k++) {
			if (dt > 0) {
				utilizationSeconds[k] += lastUtilization[k] * dt;
			}
			// Fraksi (0..1) total MIPS host yang sedang dipakai VM-VM-nya. Contoh: satu V3 sibuk
			// (10.000 MIPS) di host Tipe A (24.000 MIPS) menyumbang 10.000 / 24.000 = 0,4167.
			lastUtilization[k] = hosts.get(k).getCpuPercentUtilization();
		}
		// max(): pengaman agar waktu tidak pernah mundur bila tick datang dengan waktu yang sama/lebih kecil.
		lastTime = Math.max(lastTime, now);
	}

	/**
	 * Energi setiap host dari 0 sampai makespan, dalam Joule.
	 *
	 * <p>E_k = P_idle_k * makespan + (P_max_k - P_idle_k) * integral u_k dt.
	 * Komponen idle memakai makespan karena semua 6 host dianggap menyala sejak
	 * t = 0 sampai task terakhir selesai, walaupun tidak menjalankan VM yang sibuk
	 * (total daya idle 3 x 175 + 3 x 72 = 741 W). P_idle/P_max diambil dari
	 * {@link HostType} lewat {@link DatacenterFactory#typeOf}.</p>
	 *
	 * @param makespan Cmax hasil simulasi (detik)
	 * @return salinan array energi per host (Joule), indeks sama dengan list host
	 */
	public double[] hostJoules(double makespan) {
		for (int k = 0; k < hosts.size(); k++) {
			HostType type = DatacenterFactory.typeOf(hosts.get(k));
			joules[k] = type.idlePower * makespan
					+ (type.maxPower - type.idlePower) * utilizationSeconds[k];
		}
		return joules.clone();
	}

	/**
	 * integral u_k(t) dt untuk setiap host (detik pada utilisasi 100%).
	 *
	 * @return salinan array akumulasi utilisasi per host
	 */
	public double[] utilizationSeconds() {
		return utilizationSeconds.clone();
	}

	/**
	 * Konversi Joule ke kWh: 1 kWh = 1.000 W x 3.600 s = 3.600.000 J.
	 *
	 * @param joules energi dalam Joule
	 * @return energi dalam kWh
	 */
	public static double toKwh(double joules) {
		return joules / 3_600_000;
	}
}
