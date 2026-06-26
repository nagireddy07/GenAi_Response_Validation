package programElements;
 
import com.tyss.optimize.common.util.*;
import com.tyss.optimize.nlp.util.*;
import com.tyss.optimize.nlp.util.annotation.*;
 
import opennlp.tools.tokenize.SimpleTokenizer;
import org.simmetrics.StringMetric;
import org.simmetrics.metrics.StringMetrics;
 
import java.util.*;
 
public class GenAiResponseValidationReturnScore implements Nlp {
 
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a","about","above","after","again","against","all","am","an","and","any",
            "are","aren't","as","at","be","because","been","before","being","below",
            "between","both","but","by","can't","cannot","could","couldn't","did",
            "didn't","do","does","doesn't","doing","don't","down","during","each",
            "few","for","from","further","had","hadn't","has","hasn't","have","haven't",
            "having","he","he'd","he'll","he's","her","here","here's","hers","herself",
            "him","himself","his","how","how's","i","i'd","i'll","i'm","i've","if","in",
            "into","is","isn't","it","it's","its","itself","let's","me","more","most",
            "mustn't","my","myself","no","nor","not","of","off","on","once","only",
            "or","other","ought","our","ours","ourselves","out","over","own","same",
            "she","she'd","she'll","she's","should","shouldn't","so","some","such",
            "than","that","that's","the","their","theirs","them","themselves","then",
            "there","there's","these","they","they'd","they'll","they're","they've",
            "this","those","through","to","too","under","until","up","very","was",
            "wasn't","we","we'd","we'll","we're","we've","were","weren't","what",
            "what's","when","when's","where","where's","which","while","who","who's",
            "whom","why","why's","with","won't","would","wouldn't","you","you'd",
            "you'll","you're","you've","your","yours","yourself","yourselves",
 
            "able","above","according","accordingly","across","actually","afterwards",
            "ain't","allow","allows","almost","alone","along","already","also","although",
            "always","among","amongst","another","anybody","anyhow","anyone","anything",
            "anyway","anyways","anywhere","apart","appear","appreciate","appropriate",
            "aren't","around","aside","ask","asking","associated","available","away",
            "awfully",
 
            "become","becomes","becoming","beforehand","behind","believe","beside",
            "besides","best","better","beyond","brief","came","cannot","cause","causes",
            "certain","certainly","changes","clearly","co","com","come","comes",
            "concerning","consequently","consider","considering","contain","containing",
            "contains","corresponding","course","currently",
 
            "definitely","described","despite","different","downwards","edu","eg",
            "eight","either","else","elsewhere","enough","entirely","especially","etc",
            "even","ever","every","everybody","everyone","everything","everywhere","ex",
            "exactly","example","except",
 
            "far","few","fifth","first","five","followed","following","follows","forth",
            "four","furthermore",
 
            "get","gets","getting","given","gives","go","goes","going","gone","got",
            "gotten","greetings",
 
            "happens","hardly","hello","help","hence","hereafter","hereby","herein",
            "hereupon","hi","hither","hopefully","howbeit","however",
 
            "ie","ignored","immediate","inasmuch","inc","indeed","indicate","indicated",
            "indicates","inner","inside","insofar","instead","inward",
 
            "just",
 
            "keep","keeps","kept","know","known","knows",
 
            "last","lately","later","latter","latterly","least","less","lest","let",
            "like","liked","likely","little","look","looking","looks","ltd",
 
            "mainly","many","may","maybe","mean","meanwhile","merely","might","moreover",
            "mostly","must","my",
 
            "name","namely","nd","near","nearly","necessary","need","needs","neither",
            "never","nevertheless","new","next","nine","noone","nobody","non","none",
            "nonetheless","normally","nothing","novel","now","nowhere",
 
            "obviously","oh","ok","okay","old","once","one","ones","only","onto","other",
            "others","otherwise","ours","outside","overall",
 
            "particular","particularly","per","perhaps","placed","please","plus","possible",
            "presumably","probably","provides",
 
            "que","quite","qv",
 
            "rather","rd","re","really","reasonably","regarding","regardless","regards",
            "relatively","respectively","right",
 
            "said","saw","say","saying","says","second","secondly","see","seeing","seem",
            "seemed","seeming","seems","seen","self","selves","sensible","sent","serious",
            "seriously","seven","several","shall","shan't","since","six","somebody",
            "somehow","someone","something","sometime","sometimes","somewhat","somewhere",
            "soon","sorry","specified","specify","specifying","still","sub","such","sup",
            "sure",
 
            "take","taken","tell","tends","th","thank","thanks","thanx","that","thats",
            "the","their","theirs","them","themselves","then","thence","thereafter",
            "thereby","therefore","therein","theres","thereupon","think","third",
            "thorough","thoroughly","though","three","throughout","thru","thus","together",
            "took","toward","towards","tried","tries","truly","try","trying","twice","two",
 
            "un","under","unfortunately","unless","unlike","unlikely","until","unto",
            "upon","use","used","useful","uses","using","usually",
 
            "value","various","via","viz","vs",
 
            "want","wants","way","we","welcome","well","went","whence","whenever",
            "where","whereafter","whereas","whereby","wherein","whereupon","wherever",
            "whether","whither","whoever","whole","whose","why","will","willing","wish",
            "within","without","wonder",
 
            "yes","yet","you","your","yours","yourself","yourselves","zero"
    ));
 
    public static String preprocess(String text) {
 
        SimpleTokenizer tokenizer = SimpleTokenizer.INSTANCE;
        // PorterStemmer stemmer = new PorterStemmer();
 
        String[] tokens = tokenizer.tokenize(text.toLowerCase());
        StringBuilder sb = new StringBuilder();
 
        for (String t : tokens) {
 
            t = t.replaceAll("[^a-zA-Z0-9]", "");
 
            if (t.isEmpty()) continue;
            if (STOPWORDS.contains(t)) continue;
 
            String stemmed = t; // stemmer.stem(t);
 
            if (stemmed.length() <= 1) continue;
 
            sb.append(stemmed).append(" ");
        }
 
        return sb.toString().trim();
    }
 
    @InputParams({
            @InputParam(name = "Ref_Response", type = "java.lang.String"),
            @InputParam(name = "Act_Response", type = "java.lang.String"),
            @InputParam(name = "Confidence_Score", type = "java.lang.Float")
    })
    @ReturnType(name = "Similarity_Score", type = "java.lang.Float")
    @Override
    public NlpResponseModel execute(NlpRequestModel nlpRequestModel) throws NlpException {
 
        Map<String, Object> attributes = nlpRequestModel.getAttributes();
        String ref = (String) attributes.get("Ref_Response");
        String act = (String) attributes.get("Act_Response");
        Float confidence = (Float) attributes.get("Confidence_Score");
 
        NlpResponseModel response = new NlpResponseModel();
 
        try {
 
            String p1 = preprocess(ref);
            String p2 = preprocess(act);
 
            StringMetric metric = StringMetrics.cosineSimilarity();
            float similarity = metric.compare(p1, p2);
 
            float scorePercent = similarity * 100;
 
            System.out.println("Similarity Score: " + scorePercent);
 
            if (scorePercent >= confidence) {
                response.setMessage("Similarity Matched | Score: " + scorePercent);
                response.setStatus(CommonConstants.pass);
            } else {
                response.setMessage("Not Matched | Score: " + scorePercent);
                response.setStatus(CommonConstants.fail);
            }
 
            response.getAttributes().put("Similarity_Score", scorePercent);
 
        } catch (Exception e) {
            response.setStatus(CommonConstants.fail);
            response.setMessage("Error: " + e.getMessage());
        }
 
        return response;
    }
    
    public static void main(String[] args) throws NlpException {
    	NlpRequestModel map=new NlpRequestModel();
    	String ref=" ";
    	String act = " ";
    	
    	
    	map.getAttributes().put("Ref_Response", ref);
    	map.getAttributes().put("Act_Response", act);
    	
    	map.getAttributes().put("Confidence_Score", 50.0f);
		GenAiResponseValidationReturnScore gi=new GenAiResponseValidationReturnScore();
		gi.execute(map);
	}
}