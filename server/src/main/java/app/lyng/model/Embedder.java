package app.lyng.model;

import java.util.List;

/** 文本向量。query 为真时是查询向量（召回用），否则是文档向量（索引用）。失败抛 ModelException。 */
public interface Embedder {

    List<float[]> embed(List<String> texts, boolean query);

    /** pgvector 的文本写法：[0.1,0.2,...] */
    static String literal(float[] v) {
        StringBuilder out = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(v[i]);
        }
        return out.append(']').toString();
    }
}
