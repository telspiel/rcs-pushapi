package com.vts.rcs.pushapi.domain;

import lombok.Data;

@Data
public class JsonRequest {

	private String phoneNumber;
	private String userName;
	private String botId;
	private String type;
	private ContentMessage contentMessage;
	private String expiryTime;
	private String custRef;
	
}
