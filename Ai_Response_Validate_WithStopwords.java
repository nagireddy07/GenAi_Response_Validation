package supportAI;

import opennlp.tools.stemmer.PorterStemmer;
import opennlp.tools.tokenize.SimpleTokenizer;
import org.simmetrics.StringMetric;
import org.simmetrics.metrics.StringMetrics;
import java.util.*;

public class Ai_Response_Validate_WithStopwords {

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
    		"a","about","above","after","again","against","all","am","an","and","any","are","aren't","as",
            "at","be","because","been","before","being","below","between","both","but","by","could",
            "couldn't","did","didn't","do","does","doesn't","doing","don't","down","during","each",
            "few","for","from","further","had","hadn't","has","hasn't","have","haven't","having","he",
            "he'd","he'll","he's","her","here","here's","hers","herself","him","himself","his","how",
            "how's","i","i'd","i'll","i'm","i've","if","in","into","is","isn't","it","it's","its",
            "itself","let's","me","more","most","mustn't","my","myself","no","nor","not","of","off",
            "on","once","only","or","other","ought","our","ours","ourselves","out","over","own",
            "same","shan't","she","she'd","she'll","she's","should","shouldn't","so","some","such",
            "than","that","that's","the","their","theirs","them","themselves","then","there","there's",
            "these","they","they'd","they'll","they're","they've","this","those","through","to","too",
            "under","until","up","very","was","wasn't","we","we'd","we'll","we're","we've","were",
            "weren't","what","what's","when","when's","where","where's","which","while","who","who's",
            "whom","why","why's","with","won't","would","wouldn't","you","you'd","you'll","you're",
            "you've","your","yours","yourself","yourselves"
    ));

    // ---------------- PREPROCESS METHOD ----------------
    public static String preprocess(String text) {

        SimpleTokenizer tokenizer = SimpleTokenizer.INSTANCE;
        PorterStemmer stemmer = new PorterStemmer();

        String[] tokens = tokenizer.tokenize(text.toLowerCase());
        StringBuilder sb = new StringBuilder();

        for (String t : tokens) {

            t = t.replaceAll("[^a-zA-Z0-9]", "");
            if (t.isEmpty()) continue;
            if (STOPWORDS.contains(t)) continue;
            String stemmed = stemmer.stem(t);
            if (stemmed.length() <= 1) continue;

            sb.append(stemmed).append(" ");
        }

        return sb.toString().trim();
    }

    public static void main(String[] args) {

        String reference = "Reference_Response"; 

        String userResponse = "Actual_Response";

        String p1 = preprocess(reference);
        String p2 = preprocess(userResponse);

        StringMetric metric = StringMetrics.cosineSimilarity();
        float score = metric.compare(p1, p2);

        System.out.println("Similarity Score = " + score);
    }
}

