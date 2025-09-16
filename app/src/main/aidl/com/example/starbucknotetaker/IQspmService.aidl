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
}
