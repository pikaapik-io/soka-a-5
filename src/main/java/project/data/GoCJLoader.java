package project.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pembaca dataset GoCJ (dan dataset Synthetic yang formatnya sama): mengubah
 * file teks berisi panjang task (MI) menjadi array {@code double[]}.
 * Menerima nilai yang dipisah spasi/whitespace atau koma.
 *
 * <p><b>Peran dalam alur program:</b> ini langkah pertama setiap run.
 * {@code Experiment}, {@code EvaluatorCheck} dan {@code MakespanEnergyCorrelation}
 * memanggil {@link #load(Path)}; hasilnya (taskLength[i] = panjang task i)
 * diberikan ke scheduler KPB, ke {@code SimulationSetup} (untuk membuat
 * cloudlet), dan ke model analitik. Indeks i mengikuti urutan nilai di file,
 * yang juga menjadi urutan kedatangan task dan ID cloudlet.</p>
 *
 * <p>Format file GoCJ standar: satu angka per baris, misalnya
 * {@code 127000}. Loader ini lebih toleran: komentar diawali {@code #}, baris
 * kosong, dan beberapa nilai dalam satu baris juga didukung.</p>
 */
public final class GoCJLoader {
	// Kelas utilitas (hanya method static), constructor private.
	private GoCJLoader() { }

	/**
	 * Membaca semua panjang task dari file.
	 *
	 * <p>Alur per baris:</p>
	 * <ol>
	 *   <li>Buang bagian komentar: ambil teks sebelum {@code #} pertama, lalu trim.</li>
	 *   <li>Lewati baris yang kosong setelah komentar dibuang.</li>
	 *   <li>Pecah baris dengan pemisah koma, titik koma, atau whitespace
	 *       (regex {@code [,;\s]+}), lalu parse setiap token sebagai double.</li>
	 * </ol>
	 *
	 * @param path lokasi file dataset (misalnya src/main/resources/dataset/GoCJ_Dataset_100.txt)
	 * @return array panjang task dalam MI, urut sesuai isi file
	 * @throws IOException           bila file tidak bisa dibaca
	 * @throws NumberFormatException bila ada token yang bukan angka
	 */
	public static double[] load(Path path) throws IOException {
		List<Double> lengths = new ArrayList<>();
		for (String line : Files.readAllLines(path)) {
			// split("#", 2)[0]: semua teks setelah '#' dianggap komentar.
			String content = line.split("#", 2)[0].trim();
			if (content.isEmpty()) continue;
			for (String token : content.split("[,;\\s]+")) {
				lengths.add(Double.parseDouble(token));
			}
		}
		// Konversi List<Double> -> double[] (array primitif dipakai scheduler dan evaluator).
		return lengths.stream().mapToDouble(Double::doubleValue).toArray();
	}
}
