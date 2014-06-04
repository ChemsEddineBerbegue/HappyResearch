// Copyright (C) 2014 Guibing Guo
//
// This file is part of LibRec.
//
// LibRec is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// LibRec is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with LibRec. If not, see <http://www.gnu.org/licenses/>.
//

package librec.undefined;

import happy.coding.io.KeyValPair;
import happy.coding.io.Lists;
import happy.coding.io.Strings;
import happy.coding.math.Randoms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import librec.data.DenseVector;
import librec.data.SparseMatrix;
import librec.data.SparseVector;
import librec.data.SymmMatrix;
import librec.data.VectorEntry;
import librec.ranking.RankALS;

/**
 * Neil Hurley, <strong>Personalized Ranking with Diversity</strong>, RecSys
 * 2013.
 * 
 * <p>
 * Related Work:
 * <ul>
 * <li>Jahrer and Toscher, Collaborative Filtering Ensemble for Ranking, JMLR,
 * 2012 (KDD Cup 2011 Track 2).</li>
 * </ul>
 * </p>
 * 
 * @author guoguibing
 * 
 */
public class PRankD extends RankALS {

	// item importance
	private DenseVector s;

	// item sampling probabilities sorted ascendingly 
	private List<KeyValPair<Integer>> itemProbs;

	// item correlations
	private SymmMatrix itemCorrs;

	// similarity filter
	private double alpha;

	private boolean flag;

	public PRankD(SparseMatrix trainMatrix, SparseMatrix testMatrix, int fold) {
		super(trainMatrix, testMatrix, fold);

		algoName = "PRankD";
		isRankingPred = true;
	}

	@Override
	protected void initModel() {
		super.initModel();

		// pre-processing: binarize training data
		// super.binary(trainMatrix);
		// super.binary(testMatrix); // TODO: testing 
		numRates = trainMatrix.size();

		alpha = cf.getDouble("PRankD.alpha");

		// compute item sampling probability
		Map<Integer, Double> itemProbsMap = new HashMap<>();
		double maxUsers = 0;

		s = new DenseVector(numItems);
		for (int j = 0; j < numItems; j++) {
			int users = trainMatrix.columnSize(j);

			if (maxUsers < users)
				maxUsers = users;

			s.set(j, users);

			// sample items based on popularity
			double prob = (users + 0.0) / numRates;
			if (prob > 0)
				itemProbsMap.put(j, prob);
		}
		itemProbs = Lists.sortMap(itemProbsMap);

		// compute item relative importance
		for (int j = 0; j < numItems; j++) {
			s.set(j, s.get(j) / maxUsers);
		}

		flag = true;

		if (!flag)
			// compute item correlations by cosine similarity
			itemCorrs = buildCorrs(false);
	}

	/**
	 * override this approach to transform item similarity
	 */
	protected double correlation(SparseVector iv, SparseVector jv) {
		double sim = correlation(iv, jv, "cos-binary");

		if (Double.isNaN(sim))
			sim = 0.0;

		// to obtain a greater spread of diversity values
		return Math.tanh(alpha * sim);
	}

	@Override
	protected void buildModel() {
		for (int iter = 1; iter <= maxIters; iter++) {

			errs = 0;
			loss = 0;

			// for each rated user-item (u,i) pair
			for (int u : trainMatrix.rows()) {

				SparseVector Ru = trainMatrix.row(u);
				for (VectorEntry ve : Ru) {
					// each rated item i
					int i = ve.index();
					double rui = ve.get();

					int j = -1;
					while (true) {
						// draw an item j with probability proportional to popularity
						double sum = 0, rand = Randoms.random();
						for (KeyValPair<Integer> en : itemProbs) {
							int k = en.getKey();
							double prob = en.getValue();

							sum += prob;
							if (sum >= rand) {
								j = k;
								break;
							}
						}

						// ensure that it is unrated by user u
						if (!Ru.contains(j))
							break;
					}
					double ruj = 0;

					// compute predictions
					double pui = predict(u, i), puj = predict(u, j);
					double dij, sj;

					if (flag) {
						// the same case as RankALS
						dij = 1;
						sj = 1;
					} else {
						dij = Math.sqrt(1 - itemCorrs.get(i, j));
						sj = s.get(j);
					}

					double e = sj * (pui - puj - dij * (rui - ruj));

					errs += e * e;
					loss += e * e;

					// update vectors
					double ye = lRate * e;
					for (int f = 0; f < numFactors; f++) {
						double puf = P.get(u, f);
						double qif = Q.get(i, f);
						double qjf = Q.get(j, f);

						P.add(u, f, -ye * (qif - qjf));
						Q.add(i, f, -ye * puf);
						Q.add(j, f, ye * puf);
					}
				}
			}

			errs *= 0.5;
			loss *= 0.5;

			if (isConverged(iter))
				break;
		}
	}

	@Override
	public String toString() {
		return Strings.toString(new Object[] { binaryHold, (float) alpha, (float) lRate, maxIters }, ",");
	}
}
