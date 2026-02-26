package com.vts.rcs.pushapi.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
public class PushResponse {

	String code;
	String desc;
	@JsonIgnore
	String name;
	String receiveTime;
	String msgId;
	 @JsonIgnore
	String time;
	 @JsonIgnore
	String conversationId;
	 @JsonIgnore
	ContentMessage contentMessageRequest;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMsgId() {
		return msgId;
	}

	public String setMsgId(String msgId) {
		return this.msgId = msgId;
	}

	public ContentMessage getContentMessageRequest() {
		return contentMessageRequest;
	}

	public void setContentMessageRequest(ContentMessage contentMessageRequest) {
		this.contentMessageRequest = contentMessageRequest;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public String getConversationId() {
		return conversationId;
	}

	public String setConversationId(String conversationId) {
		return this.conversationId = conversationId;
	}

	public String getReceiveTime() {
		return receiveTime;
	}

	public void setReceiveTime(String receiveTime) {
		this.receiveTime = receiveTime;
	}

}
