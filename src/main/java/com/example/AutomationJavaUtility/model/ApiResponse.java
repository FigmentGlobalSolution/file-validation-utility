package com.example.AutomationJavaUtility.model;


public class ApiResponse {

    private int status;      // 1 = success, 0 = failure
    private String message;
    private Object data;

    public ApiResponse(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public ApiResponse(int status, String message, Object data) {
        this.status = status;
        this.message = message;
        this.data = data;
    }

    public int getStatus() { return status; }
    public String getMessage() { return message; }
    public Object getData() { return data; }
}