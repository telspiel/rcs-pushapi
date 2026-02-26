package com.vts.rcs.pushapi.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class ContentMessage {
	private String text;
	private TemplateMessage templateMessage;
	private String templateCode;
	private List<Suggestion> suggestions;
	private ContentInfo contentInfo;
	private String fileName;
	private RichCard richCard;

	@Data
	public static class RichCard {
		private StandaloneCard standaloneCard;
		private CarouselCard carouselCard;
	}

	@Data
	public static class StandaloneCard {
		private CardContent cardContent;
		private String thumbnailImageAlignment;
		private String cardOrientation;
	}

	@Data
	public static class CarouselCard {
		private ArrayList<CardContent> cardContents;
		private String cardWidth;
		private String cardOrientation;
	}

	@Data
	public static class CardContent {
		private Media media;
		private String title;
		private String description;
		private List<Suggestion> suggestions;


	}

	@Data
	public static class Media {
		private ContentInfo contentInfo;
		private String height;
		
	}

	@Data
	public static class ContentInfo {
		private String fileUrl;
		private boolean forceRefresh;
		private String thumbnailUrl;
	}

	@Data
	public static class Suggestion {
		private Reply reply;
		private Action action;
	}

	@Data
	public static class Reply {
		private String text;
		private String postbackData;
	}

	@Data
	public static class Action {
		private String text;
		private String postbackData;
		private OpenUrlAction openUrlAction;
		private ViewLocationAction viewLocationAction;
		private ShareLocationAction shareLocationAction;
	}

	@Data
	public static class OpenUrlAction {
		private String url;
	}

	@Data
	public static class ViewLocationAction {
		private LatLong latLong;
		private String label;
	}

	@Data
	public static class LatLong {
		private double latitude;
		private double longitude;
	}

	@Data
	public static class ShareLocationAction {
	}

	@Data
	public static class TemplateMessage {
		private String templateCode;
		private String customParams;

	}
}
