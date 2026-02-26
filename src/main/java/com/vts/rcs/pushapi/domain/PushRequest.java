package com.vts.rcs.pushapi.domain;

import com.vts.rcs.domain.platform.VtUser;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PushRequest {

	private String phoneNumber;
	private String type;
	private String botId;
	private String messageId;
	private String conversationId;
	private boolean enableFallback;
	private boolean sendGipLink;
	private VtUser user;
	private ContentMessage contentMessage;
	private String custRef;

	public PushRequest(String phoneNumber, String type, String botId, VtUser user, ContentMessage contentMessage, String custRef) {
		this.phoneNumber = phoneNumber;
		this.type = type;
		this.botId = botId;
		this.user = user;
		this.contentMessage = contentMessage;
		this.custRef = custRef;
	}

}
