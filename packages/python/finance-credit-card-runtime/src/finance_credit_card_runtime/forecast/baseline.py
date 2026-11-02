"""仅用训练样本的经验基线。"""


def empirical(rows):
    import numpy as np

    return {
        str(h): {
            "quantiles": [
                float(v) for v in np.quantile([r.amounts[i] for r in rows], [0.1, 0.5, 0.9])
            ],
            "probability": sum(r.events[i] for r in rows) / len(rows),
        }
        for i, h in enumerate((1, 3, 7))
    }
