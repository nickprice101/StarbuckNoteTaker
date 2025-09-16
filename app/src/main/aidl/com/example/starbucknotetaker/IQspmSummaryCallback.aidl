package com.example.starbucknotetaker;

interface IQspmSummaryCallback {
    /** Called when summarization completed. */
    void onComplete(String summary, boolean usedFallback);

    /** Called if summarization fails before producing output. */
    void onError(String message);
}
