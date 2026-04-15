package com.example.starbucknotetaker;

import com.example.starbucknotetaker.IQspmSummaryCallback;

interface IQspmService {
    const int STATE_READY = 0;
    const int STATE_LOADING = 1;
    const int STATE_FALLBACK = 2;
    const int STATE_ERROR = 3;

    boolean isPinSet();
    int getPinLength();
    boolean verifyPin(String pin);
    boolean storePin(String pin);
    boolean updatePin(String oldPin, String newPin);
    void clearPin();

    int getSummarizerState();
    int warmUpSummarizer();
    void summarize(String text, IQspmSummaryCallback callback);

    /** Rewrites the given text in a clean, professional style via Llama 3.1 8B. */
    void rewrite(String text, IQspmSummaryCallback callback);

    /** Answers the given question, optionally grounded in the provided context. */
    void ask(String question, String context, IQspmSummaryCallback callback);
}
