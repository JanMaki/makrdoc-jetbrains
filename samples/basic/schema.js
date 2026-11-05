module.exports = {
    tags: {
        callout: {
            render: "Callout",
            description: "Highlighted note block",
            children: ["paragraph", "list"],
            attributes: {
                type: {type: String, default: "note", matches: ["note", "warning", "caution"]},
                title: {type: String},
            },
        },
        figure: {
            render: "Figure",
            selfClosing: true,
            attributes: {
                src: {type: String, required: true},
                caption: {type: String},
            },
        },
        // 配列・オブジェクトの属性値の色分けの確認用（docs/collections.mdoc）
        map: {
            render: "Map",
            selfClosing: true,
            attributes: {
                coordinates: {type: Array},
                meta: {type: Object},
            },
        },
    },
    // 変数の補完（`{% $`、`$user.`）と色分け（`$user.roles[0]`）の確認用
    variables: {
        user: {
            name: "Alice",
            profile: {email: "alice@example.com", "display-name": "alice"},
            roles: [{id: "admin", label: "Administrator"}],
        },
        flags: {beta: true},
        version: "1.0.0",
    },
    // 関数名の補完の確認用（組み込みの equals などと一緒に出る）
    functions: {
        uppercase: {
            description: "Converts a string to upper case",
            transform(parameters) {
                return String(parameters[0] ?? "").toUpperCase();
            },
        },
        includes: {
            description: "Checks whether an array contains a value",
            transform(parameters) {
                return Array.isArray(parameters[0]) && parameters[0].includes(parameters[1]);
            },
        },
    },
};
