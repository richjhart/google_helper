package com.rjhartsoftware.googlehelper;

class BackgroundActivityOnMainThreadException extends RuntimeException {
    BackgroundActivityOnMainThreadException() {
        super();
    }

    BackgroundActivityOnMainThreadException(String reason) {
        super(reason);
    }
}
