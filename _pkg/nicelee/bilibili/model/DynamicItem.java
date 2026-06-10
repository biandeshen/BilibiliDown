package nicelee.bilibili.model;

public class DynamicItem {
	private String dynamicId;
	private String uid;
	private String upName;
	private String type;
	private String bvid;
	private String title;
	private String cover;
	private Long pubTimestamp;
	private String description;
	private String durationText;

	public String getDynamicId() { return dynamicId; }
	public void setDynamicId(String dynamicId) { this.dynamicId = dynamicId; }
	public String getUid() { return uid; }
	public void setUid(String uid) { this.uid = uid; }
	public String getUpName() { return upName; }
	public void setUpName(String upName) { this.upName = upName; }
	public String getType() { return type; }
	public void setType(String type) { this.type = type; }
	public String getBvid() { return bvid; }
	public void setBvid(String bvid) { this.bvid = bvid; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getCover() { return cover; }
	public void setCover(String cover) { this.cover = cover; }
	public Long getPubTimestamp() { return pubTimestamp; }
	public void setPubTimestamp(Long pubTimestamp) { this.pubTimestamp = pubTimestamp; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public String getDurationText() { return durationText; }
	public void setDurationText(String durationText) { this.durationText = durationText; }
}
