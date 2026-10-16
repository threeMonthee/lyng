package app.lyng.model;

/** 每次模型调用的用途。决定用哪个模型配置，也是幕后观察与记账的分类。 */
public enum Purpose {
    OPENING("narrator", "叙事者开场"),
    NARRATOR("narrator-round", "叙事者"),
    CLOSING("narrator", "叙事者收尾"),
    CHARACTER("character", "开口"),
    CONSENT("check", "同意检查"),
    CONSOLIDATE("consolidate", "整理内心"),
    ASK("create", "构思追问"),
    WRITE("create", "写稿"),
    REVISE("create", "局部修改"),
    CHECK("create", "一致性检查");

    private final String role;
    private final String label;

    Purpose(String role, String label) {
        this.role = role;
        this.label = label;
    }

    public String role() {
        return role;
    }

    public String label() {
        return label;
    }
}
