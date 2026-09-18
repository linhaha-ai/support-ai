CREATE TABLE acceptance_ollama_vectors (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL,
    embedding vector(1024) NOT NULL
);

CREATE TABLE acceptance_dashscope_vectors (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL,
    embedding vector(1024) NOT NULL
);
