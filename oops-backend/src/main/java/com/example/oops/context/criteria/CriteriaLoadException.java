package com.example.oops.context.criteria;

/** 기준 데이터를 읽지 못했거나 형식이 맞지 않는다. 분석 실패로 처리한다(정보 부족이 아니다). */
public class CriteriaLoadException extends RuntimeException {

    public CriteriaLoadException(String message) {
        super(message);
    }

    public CriteriaLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
