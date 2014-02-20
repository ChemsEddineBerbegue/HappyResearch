package librec.undefined;

import librec.data.DenseMatrix;
import librec.data.MatrixEntry;
import librec.data.SparseMatrix;
import librec.data.SparseVector;
import librec.intf.SocialRecommender;

/**
 * Hao Ma, Irwin King and Michael R. Lyu, <strong>Learning to Recommend with
 * Social Trust Ensemble</strong>, SIGIR 2009.
 * 
 * @author guoguibing
 * 
 */
public class RSTE extends SocialRecommender {

	private double alpha;

	public RSTE(SparseMatrix trainMatrix, SparseMatrix testMatrix, int fold) {
		super(trainMatrix, testMatrix, fold);

		algoName = "RSTE";

		initByNorm = false;
		alpha = 0.4;
	}

	@Override
	protected void buildModel() {
		for (int iter = 1; iter <= maxIters; iter++) {

			loss = 0;
			errs = 0;

			DenseMatrix PS = new DenseMatrix(numUsers, numFactors);
			DenseMatrix QS = new DenseMatrix(numItems, numFactors);

			// ratings
			for (MatrixEntry me : trainMatrix) {
				int u = me.row();
				int j = me.column();
				double rate = me.get();

				if (rate <= 0)
					continue;

				double ruj = normalize(rate);
				double pred = predict(u, j, false);
				double euj = g(pred) - ruj;

				errs += euj * euj;
				loss += euj * euj;

				double csgd = gd(pred) * euj;

				SparseVector tu = socialMatrix.row(u);
				int[] tks = tu.getIndex();
				double[] sum_us = new double[numFactors];
				//double wk = Math.sqrt(tu.getCount());
				for (int k : tks) {
					for (int f = 0; f < numFactors; f++)
						sum_us[f] += tu.get(k) * P.get(k, f);
				}

				for (int f = 0; f < numFactors; f++) {
					double puf = P.get(u, f);
					double qjf = Q.get(j, f);

					double usgd = alpha * csgd * qjf + regU * puf;
					double jsgd = csgd * (alpha * puf + (1 - alpha) * sum_us[f]) + regI * qjf;

					PS.add(u, f, usgd);
					QS.add(j, f, jsgd);

					loss += regU * puf * puf + regI * qjf * qjf;
				}
			}

			// social
			for (int u = 0; u < numUsers; u++) {
				SparseVector bu = socialMatrix.column(u);
				int[] bps = bu.getIndex();
				if (bu.getCount() == 0)
					continue;

				double[] sum_ps = new double[numFactors];
				for (int p : bps) {
					if (p >= trainMatrix.numRows())
						continue;

					SparseVector pr = trainMatrix.row(p);
					for (int j : pr.getIndex()) {
						double rpj = pr.get(j);
						if (rpj > 0) {
							double pred = predict(p, j, false);
							double epj = g(pred) - normalize(rpj);
							double tpu = bu.get(p);
							double csgd = gd(pred) * epj * tpu;

							for (int f = 0; f < numFactors; f++)
								sum_ps[f] += csgd * Q.get(j, f);
						}
					}
				}

				for (int f = 0; f < numFactors; f++)
					PS.add(u, f, (1 - alpha) * sum_ps[f]);

			}

			loss *= 0.5;
			errs *= 0.5;

			P = P.add(PS.scale(-lRate));
			Q = Q.add(QS.scale(-lRate));

			if (isConverged(iter))
				break;
		}
	}

	@Override
	protected double predict(int u, int j, boolean bound) {
		double pred1 = DenseMatrix.rowMult(P, u, Q, j);
		double pred2 = 0.0;
		SparseVector tu = socialMatrix.row(u);
		for (int k : tu.getIndex())
			pred2 += tu.get(k) * DenseMatrix.rowMult(P, k, Q, j);

		double pred = alpha * pred1 + (1 - alpha) * pred2;

		if (bound)
			return denormalize(g(pred));

		return pred;
	}

}
