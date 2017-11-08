/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror;

import java.util.Set;

public class AuditLogContext {

  private String errorMessage;
  private Set<String> headers = null;
  private String aclHistory;
  private String origin;
  private String finalState;
  private Integer taskId;
  private String type;
  private String reqMethod;
  private String path;
  private Set<String> indices = null;
  private String timestamp;
  private Integer contentLenKb;
  private String errorType;
  private Long processingMillis;
  private String action;
  private String matchedBlock;
  private String id;
  private Integer contentLen;
  private String user;

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public AuditLogContext withErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
    return this;
  }

  public Set<String> getHeaders() {
    return headers;
  }

  public void setHeaders(Set<String> headers) {
    this.headers = headers;
  }

  public AuditLogContext withHeaders(Set<String> headers) {
    this.headers = headers;
    return this;
  }

  public String getAclHistory() {
    return aclHistory;
  }

  public void setAclHistory(String aclHistory) {
    this.aclHistory = aclHistory;
  }

  public AuditLogContext withAclHistory(String aclHistory) {
    this.aclHistory = aclHistory;
    return this;
  }

  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String origin) {
    this.origin = origin;
  }

  public AuditLogContext withOrigin(String origin) {
    this.origin = origin;
    return this;
  }

  public String getFinalState() {
    return finalState;
  }

  public void setFinalState(String finalState) {
    this.finalState = finalState;
  }

  public AuditLogContext withFinalState(String finalState) {
    this.finalState = finalState;
    return this;
  }

  public Integer getTaskId() {
    return taskId;
  }

  public void setTaskId(Integer taskId) {
    this.taskId = taskId;
  }

  public AuditLogContext withTaskId(Integer taskId) {
    this.taskId = taskId;
    return this;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public AuditLogContext withType(String type) {
    this.type = type;
    return this;
  }

  public String getReqMethod() {
    return reqMethod;
  }

  public void setReqMethod(String reqMethod) {
    this.reqMethod = reqMethod;
  }

  public AuditLogContext withReqMethod(String reqMethod) {
    this.reqMethod = reqMethod;
    return this;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public AuditLogContext withPath(String path) {
    this.path = path;
    return this;
  }

  public Set<String> getIndices() {
    return indices;
  }

  public void setIndices(Set<String> indices) {
    this.indices = indices;
  }

  public AuditLogContext withIndices(Set<String> indices) {
    this.indices = indices;
    return this;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public AuditLogContext withTimestamp(String timestamp) {
    this.timestamp = timestamp;
    return this;
  }

  public Integer getContentLenKb() {
    return contentLenKb;
  }

  public void setContentLenKb(Integer contentLenKb) {
    this.contentLenKb = contentLenKb;
  }

  public AuditLogContext withContentLenKb(Integer contentLenKb) {
    this.contentLenKb = contentLenKb;
    return this;
  }

  public String getErrorType() {
    return errorType;
  }

  public void setErrorType(String errorType) {
    this.errorType = errorType;
  }

  public AuditLogContext withErrorType(String errorType) {
    this.errorType = errorType;
    return this;
  }

  public Long getProcessingMillis() {
    return processingMillis;
  }

  public void setProcessingMillis(Long processingMillis) {
    this.processingMillis = processingMillis;
  }

  public AuditLogContext withProcessingMillis(Long processingMillis) {
    this.processingMillis = processingMillis;
    return this;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public AuditLogContext withAction(String action) {
    this.action = action;
    return this;
  }

  public String getMatchedBlock() {
    return matchedBlock;
  }

  public void setMatchedBlock(String matchedBlock) {
    this.matchedBlock = matchedBlock;
  }

  public AuditLogContext withMatchedBlock(String matchedBlock) {
    this.matchedBlock = matchedBlock;
    return this;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public AuditLogContext withId(String id) {
    this.id = id;
    return this;
  }

  public Integer getContentLen() {
    return contentLen;
  }

  public void setContentLen(Integer contentLen) {
    this.contentLen = contentLen;
  }

  public AuditLogContext withContentLen(Integer contentLen) {
    this.contentLen = contentLen;
    return this;
  }

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public AuditLogContext withUser(String user) {
    this.user = user;
    return this;
  }

}