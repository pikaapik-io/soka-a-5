package project.model;

import java.util.Arrays;

 
public final class ScheduleEvaluator {
	private final double[] taskLength;
	private final double[] vmCapacity;
	private final int[] vmHost;
	private final double[] hostIdlePower;
	private final double[] hostMaxPower;
	private final double[] hostTotalMips;
	private final int vmCount;
	private final int hostCount;

	/**
	 * Menyalin semua data dari context ke field lokal (getter context sudah
	 * mengembalikan salinan), supaya evaluasi berulang tidak perlu clone lagi.
	 *
	 * @param context task, kapasitas VM, penempatan VM -&gt; host, dan parameter daya host
	 */
	public ScheduleEvaluator(ScheduleContext context) {
		this.taskLength = context.taskLength();
		this.vmCapacity = context.vmCapacity();
		this.vmHost = context.vmHost();
		this.hostIdlePower = context.hostIdlePower();
		this.hostMaxPower = context.hostMaxPower();
		this.hostTotalMips = context.hostTotalMips();
		this.vmCount = vmCapacity.length;
		this.hostCount = hostIdlePower.length;
	}

	/**
	 * Menghitung beban (load) setiap VM: load_j = jumlah L_i / C_j atas task yang
	 * ditugaskan ke VM j. O(jumlah task).
	 *
	 * <p>Karena VM mengerjakan task secara berurutan (SpaceShared/FIFO), load_j
	 * sama dengan waktu selesai task terakhir di VM j (semua task tiba di t = 0).
	 * Nilai ini sama dengan readyTime akhir di KpbScheduler.</p>
	 *
	 * @param mapping mapping[i] = indeks VM untuk task i
	 * @return loads[j] = total waktu sibuk VM j (detik); 0 untuk VM yang tidak dipakai
	 * @throws IllegalArgumentException bila mapping tidak valid
	 */
	public double[] vmLoads(int[] mapping) {
		validateMapping(mapping);
		double[] loads = new double[vmCount];
		for (int task = 0; task < mapping.length; task++) {
			int vm = mapping[task];
			// Tambahkan waktu eksekusi task ini (ETC = L / C) ke antrean VM tujuannya.
			loads[vm] += taskLength[task] / vmCapacity[vm];
		}
		return loads;
	}

	/**
	 * Makespan analitik (desain f1): waktu sampai task terakhir selesai,
	 * yaitu max_j load_j.
	 *
	 * @param mapping mapping task ke VM
	 * @return makespan dalam detik
	 */
	public double makespan(int[] mapping) {
		return makespanFromLoads(vmLoads(mapping));
	}

	/**
	 * Makespan dari array load yang sudah dihitung: nilai load terbesar.
	 *
	 * @param loads load setiap VM (detik)
	 * @return max_j load_j, atau 0 bila array kosong
	 */
	public double makespanFromLoads(double[] loads) {
		return Arrays.stream(loads).max().orElse(0);
	}

	/**
	 * Rincian energi (idle + dinamis) untuk sebuah mapping.
	 *
	 * @param mapping mapping task ke VM
	 * @return objek EnergyBreakdown dalam Joule
	 */
	public EnergyBreakdown energyBreakdown(int[] mapping) {
		return energyBreakdownFromLoads(vmLoads(mapping));
	}

	/**
	 * Energi idle + dinamis dalam Joule untuk load VM yang diberikan. O(jumlah VM + jumlah host).
	 *
	 * @param loads load setiap VM (detik), hasil {@link #vmLoads}
	 * @return rincian energi idle dan dinamis
	 */
	public EnergyBreakdown energyBreakdownFromLoads(double[] loads) {
		double makespan = makespanFromLoads(loads);
		// Bagian idle: SEMUA host (termasuk host tanpa VM sibuk) menyala dari t = 0
		// sampai makespan, sehingga masing-masing menyumbang P_idle_h * makespan.
		double idle = 0;
		for (int h = 0; h < hostCount; h++) {
			idle += hostIdlePower[h] * makespan;
		}
		// Bagian dinamis: setiap VM j menyumbang (P_max_h - P_idle_h) x utilisasi
		// yang ia berikan ke host (C_j / MIPS_h) x lama ia sibuk (load_j).
		double dynamic = 0;
		for (int vm = 0; vm < vmCount; vm++) {
			int h = vmHost[vm];
			dynamic += (hostMaxPower[h] - hostIdlePower[h]) * (vmCapacity[vm] / hostTotalMips[h]) * loads[vm];
		}
		return new EnergyBreakdown(idle, dynamic);
	}

	/**
	 * Total energi analitik dalam Joule.
	 *
	 * @param mapping mapping task ke VM
	 * @return energi idle + dinamis (J)
	 */
	public double energyJoules(int[] mapping) {
		return energyBreakdown(mapping).totalJoules();
	}

	/**
	 * Total energi analitik dalam kWh (1 kWh = 3.600.000 J).
	 *
	 * @param mapping mapping task ke VM
	 * @return energi (kWh)
	 */
	public double energyKwh(int[] mapping) {
		return energyJoules(mapping) / 3_600_000;
	}

	/** @return jumlah VM */
	public int vmCount() { return vmCount; }
	/** @return jumlah host */
	public int hostCount() { return hostCount; }
	/** @return jumlah task */
	public int taskCount() { return taskLength.length; }

	/**
	 * Memastikan mapping berisi tepat satu VM untuk setiap task dan setiap indeks VM valid.
	 *
	 * @throws IllegalArgumentException bila mapping null, panjangnya berbeda dengan
	 *                                  jumlah task, atau berisi indeks VM di luar 0..vmCount-1
	 */
	private void validateMapping(int[] mapping) {
		if (mapping == null || mapping.length != taskLength.length) {
			throw new IllegalArgumentException("Mapping must contain one VM for every task");
		}
		for (int vm : mapping) {
			if (vm < 0 || vm >= vmCount) {
				throw new IllegalArgumentException("Mapping contains an invalid VM index");
			}
		}
	}

	/**
	 * Energi yang dipecah menjadi komponen idle (selalu menyala) dan dinamis
	 * (bergantung pada beban). Pemisahan ini berguna untuk menjelaskan mengapa
	 * konsolidasi (k kecil) tidak selalu menghemat energi: komponen idle 6 host
	 * tetap dibayar selama makespan, sehingga makespan yang lebih panjang
	 * menaikkan energi total.
	 */
	public static final class EnergyBreakdown {
		private final double idleJoules;
		private final double dynamicJoules;

		/**
		 * @param idleJoules    energi idle (J)
		 * @param dynamicJoules energi dinamis (J)
		 */
		public EnergyBreakdown(double idleJoules, double dynamicJoules) {
			this.idleJoules = idleJoules;
			this.dynamicJoules = dynamicJoules;
		}

		/** @return energi idle (J) */
		public double idleJoules() { return idleJoules; }
		/** @return energi dinamis (J) */
		public double dynamicJoules() { return dynamicJoules; }
		/** @return energi total = idle + dinamis (J) */
		public double totalJoules() { return idleJoules + dynamicJoules; }
		/** @return energi total dalam kWh (dibagi 3.600.000) */
		public double totalKwh() { return totalJoules() / 3_600_000; }
		/** @return porsi energi dinamis terhadap total (0..1); 0 bila total 0 untuk menghindari bagi nol */
		public double dynamicShare() { return totalJoules() == 0 ? 0 : dynamicJoules / totalJoules(); }
	}
}
