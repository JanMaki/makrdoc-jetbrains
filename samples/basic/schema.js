module.exports = {
  tags: {
    callout: {
      render: "Callout",
      description: "Highlighted note block",
      children: ["paragraph", "list"],
      attributes: {
        type: { type: String, default: "note", matches: ["note", "warning", "caution"] },
        title: { type: String },
      },
    },
    figure: {
      render: "Figure",
      selfClosing: true,
      attributes: {
        src: { type: String, required: true },
        caption: { type: String },
      },
    },
  },
};
