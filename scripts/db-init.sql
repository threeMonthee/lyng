-- 容器第一次启动时执行：测试库与向量扩展。
CREATE DATABASE lyng_test OWNER lyng;
\c lyng
CREATE EXTENSION IF NOT EXISTS vector;
\c lyng_test
CREATE EXTENSION IF NOT EXISTS vector;
