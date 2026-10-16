package app.lyng.model;

import java.util.List;

/**
 * 确定性替身：把字符二元组哈希进固定维度并归一化。用词相近的句子向量相近，测试里召回是真的在比相似度。
 */
public final class FakeEmbedder implements Embedder {

    static final int DIMENSION = 256;

    @Override
    public List<float[]> embed(List<String> texts, boolean query) {
        return texts.stream().map(FakeEmbedder::vector).toList();
    }

    static float[] vector(String text) {
        float[] v = new float[DIMENSION];
        String s = text.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < s.length(); i++) {
            int h = Math.floorMod(s.substring(i, i + 2).hashCode(), DIMENSION);
            v[h] += 1;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1;
            return v;
        }
        float n = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] /= n;
        }
        return v;
    }
}
