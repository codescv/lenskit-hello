/*
 * Copyright 2011 University of Minnesota
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.grouplens.lenskit.hello;

import org.grouplens.lenskit.ItemRecommender;
import org.grouplens.lenskit.ItemScorer;
import org.grouplens.lenskit.Recommender;
import org.grouplens.lenskit.RecommenderBuildException;
import org.grouplens.lenskit.baseline.BaselineScorer;
import org.grouplens.lenskit.baseline.ItemMeanRatingItemScorer;
import org.grouplens.lenskit.baseline.UserMeanBaseline;
import org.grouplens.lenskit.baseline.UserMeanItemScorer;
import org.grouplens.lenskit.core.LenskitConfiguration;
import org.grouplens.lenskit.core.LenskitRecommender;
import org.grouplens.lenskit.data.dao.EventDAO;
import org.grouplens.lenskit.data.dao.ItemNameDAO;
import org.grouplens.lenskit.data.dao.SimpleFileRatingDAO;
import org.grouplens.lenskit.data.history.LikeCountUserHistorySummarizer;
import org.grouplens.lenskit.data.history.UserHistorySummarizer;
import org.grouplens.lenskit.data.text.*;
import org.grouplens.lenskit.knn.item.ItemItemScorer;
import org.grouplens.lenskit.knn.item.NeighborhoodScorer;
import org.grouplens.lenskit.knn.item.SimilaritySumNeighborhoodScorer;
import org.grouplens.lenskit.scored.ScoredId;
import org.grouplens.lenskit.transform.normalize.BaselineSubtractingUserVectorNormalizer;
import org.grouplens.lenskit.transform.normalize.UserVectorNormalizer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Demonstration app for LensKit. This application builds an item-item CF model
 * from a CSV file, then generates recommendations for a user.
 *
 * Usage: java org.grouplens.lenskit.hello.HelloLenskit ratings.csv user
 */
public class HelloLenskit implements Runnable {
    public static void main(String[] args) {
        HelloLenskit hello = new HelloLenskit(args);
        try {
            hello.run();
        } catch (RuntimeException e) {
            System.err.println(e.toString());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private List<Long> users;

    public HelloLenskit(String[] args) {
        int nextArg = 0;
        users = new ArrayList<Long>(args.length - nextArg);
        for (; nextArg < args.length; nextArg++) {
            users.add(Long.parseLong(args[nextArg]));
        }

        System.out.println("users:" + users);
    }


    private LenskitConfiguration binaryItemCFConfig(String inputFileName) {
        File inputFile = new File(inputFileName);

        DelimitedColumnEventFormat fmt =
                DelimitedColumnEventFormat.create(new LikeEventType())
                        .setDelimiter(",")
                        .setFields(Fields.user(), Fields.item(),
                                Fields.timestamp())
                        .setHeaderLines(1);
        EventDAO dao = TextEventDAO.create(inputFile, fmt);

        // Second step is to create the LensKit configuration...
        LenskitConfiguration config = new LenskitConfiguration();
        // ... configure the data source
        config.addComponent(dao);
        // ... and configure the item scorer.  The bind and set methods
        // are what you use to do that. Here, we want an item-item scorer.
        config.bind(ItemScorer.class)
                .to(ItemItemScorer.class);

        config.bind(UserHistorySummarizer.class).to(LikeCountUserHistorySummarizer.class);

        config.bind(NeighborhoodScorer.class).to(SimilaritySumNeighborhoodScorer.class);

        return config;
    }

    private LenskitConfiguration ratingItemCFConfig(String inputFileName) {
        File inputFile = new File(inputFileName);

        EventDAO dao = TextEventDAO.create(inputFile, Formats.movieLensLatest());

        // Second step is to create the LensKit configuration...
        LenskitConfiguration config = new LenskitConfiguration();
        // ... configure the data source
        config.addComponent(dao);
        // ... and configure the item scorer.  The bind and set methods
        // are what you use to do that. Here, we want an item-item scorer.
        config.bind(ItemScorer.class)
                .to(ItemItemScorer.class);

        // let's use personalized mean rating as the baseline/fallback predictor.
        // 2-step process:
        // First, use the user mean rating as the baseline scorer
        config.bind(BaselineScorer.class, ItemScorer.class)
               .to(UserMeanItemScorer.class);
        // Second, use the item mean rating as the base for user means
        config.bind(UserMeanBaseline.class, ItemScorer.class)
              .to(ItemMeanRatingItemScorer.class);
        // and normalize ratings by baseline prior to computing similarities
        config.bind(UserVectorNormalizer.class)
              .to(BaselineSubtractingUserVectorNormalizer.class);

        return config;
    }

    public void run() {
        // We first need to configure the data access.
        // We will use a simple delimited file; you can use something else like
        // a database (see JDBCRatingDAO).
        LenskitConfiguration config = binaryItemCFConfig("data/likes.csv");
//        LenskitConfiguration config = ratingItemCFConfig("data/ratings.csv");

        // There are more parameters, roles, and components that can be set. See the
        // JavaDoc for each recommender algorithm for more information.

        // Now that we have a factory, build a recommender from the configuration
        // and data source. This will compute the similarity matrix and return a recommender
        // that uses it.
        Recommender rec = null;
        try {
            rec = LenskitRecommender.build(config);
        } catch (RecommenderBuildException e) {
            throw new RuntimeException("recommender build failed", e);
        }

        // we want to recommend items
        ItemRecommender irec = rec.getItemRecommender();
        assert irec != null; // not null because we configured one
        // for users
        for (long user: users) {
            // get 10 recommendation for the user
            List<ScoredId> recs = irec.recommend(user, 20);
            System.out.format("Recommendations for %d:\n", user);
            for (ScoredId item: recs) {
                System.out.format("\t%d\t%.2f\n", item.getId(), item.getScore());
            }
        }
    }
}
