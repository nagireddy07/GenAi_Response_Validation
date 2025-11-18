AI Response Validation Utilities

This repository contains two Java utilities designed to validate AI-generated responses using different NLP techniques:

Cosine Similarity Based Response Validation (with preprocessing + stopwords + stemming)

Keyword Containment Based Validation (with stopwords removal)

These utilities help in checking if a user’s response matches an expected reference response with measurable accuracy.

📌 Files Included
1. Ai_Response_Validate_WithStopwords.java

Performs text similarity using Cosine Similarity with the following NLP steps:

Tokenization

Stopwords removal

Stemming (Porter Stemmer)

Special character cleaning

Cosine similarity comparison using SimMetrics

Features

Removes common English stopwords.

Applies stemming to normalize words.

Calculates cosine similarity score between reference and actual response.

Useful when semantic similarity is required.

2. Match_With_Stopwords.java

Checks keyword containment percentage between two responses:

Removes stopwords

Normalizes words to lowercase

Calculates how many meaningful words from the actual response appear in the expected response

Features

Simple, interpretable matching approach.

Returns containment score as a percentage.

Prints validity status based on a threshold (default: 60%).

🧠 How It Works
Cosine Similarity Flow
Input → Tokenize → Clean text → Remove Stopwords → Stem → Cosine Similarity → Score

Containment Flow
Input → Remove Stopwords → Extract unique words → Count matches → Percentage → Validity

📦 Dependencies
For Ai_Response_Validate_WithStopwords.java:
<!-- Apache OpenNLP -->
<dependency>
    <groupId>org.apache.opennlp</groupId>
    <artifactId>opennlp-tools</artifactId>
    <version>1.9.4</version>
</dependency>

<!-- SimMetrics -->
<dependency>
    <groupId>com.github.mpkorstanje</groupId>
    <artifactId>simmetrics-core</artifactId>
    <version>4.1.1</version>
</dependency>

For Both Files:

Java 8+

No external configuration needed

🚀 Execution
Run Cosine Similarity Validator
java supportAI.Ai_Response_Validate_WithStopwords


Sample Output:

Similarity Score = 0.86

Run Containment Validator
java supportAI.Match_With_Stopwords


Sample Output:

Containment Score: 72.50%
isValid : true

⚙️ Customization

You can modify:

Stopwords list

Confidence thresholds

Expected/actual responses

Preprocessing rules

Both classes have a simple structure designed for easy extension.

📝 Use Cases

AI/Chatbot response validation

NLP-based answer checking

Automation workflows (QA, Fireflink, Selenium tests)

Model output comparison

Similarity-based automated grading
