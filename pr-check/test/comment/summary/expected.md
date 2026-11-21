## 🎅 SANTA · owner/repo

base `1111111` → head `2222222`

| 🪨 newly red | 🎁 now green | ↔ red another way | still red |
|---:|---:|---:|---:|
| 2 | 1 | 1 | 4 |

### 🪨 Newly red: 2 entries in 1 file

| slice | file | entries | dimensions |
|---|---|---:|---|
| <code>eval/v6/authored</code> | <code>eval/v6/authored/A.json</code> | 2 | value 2 · cost 1 |

### 🎁 Now green: 1 entry in 1 file

| slice | file | entries | dimensions |
|---|---|---:|---|
| <code>eval/v6/authored</code> | <code>eval/v6/authored/B.json</code> | 1 | value 1 |

<details><summary>↔ Red another way: 1 entry in 1 file</summary>

| slice | file | entries | change |
|---|---|---:|---|
| <code>eval/v6/authored</code> | <code>eval/v6/authored/A.json</code> | 1 | value → cost 1 |

</details>

### 🪨 Newly red: 2 entries in 1 file, per entry


**<code>eval/v6/authored</code> · <code>eval/v6/authored/A.json</code>**

- <code>e1</code>: value: expected <code>1</code>, got <code>2</code>
- <code>e2</code>: cost: expected <code>5</code>, got <code>6</code>; value: expected <code>1</code>, got <code>3</code>

### ↔ Red another way: 1 entry in 1 file, per entry


**<code>eval/v6/authored</code> · <code>eval/v6/authored/A.json</code>**

- <code>c1</code>: base value: expected <code>1</code>, got <code>2</code> → head cost: expected <code>5</code>, got <code>7</code>

### 🎁 Now green: 1 entry in 1 file, per entry


**<code>eval/v6/authored</code> · <code>eval/v6/authored/B.json</code>**

- <code>g1</code>: was value: expected <code>1</code>, got <code>2</code>
