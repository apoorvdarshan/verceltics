const ENTITIES = {
  "&amp;": "&", "&quot;": '"', "&lt;": "<", "&gt;": ">", "&#39;": "'", "&#x27;": "'",
};

/** Decode the entities emitted in our static attributes exactly once. */
export function decodeHtml(value) {
  return value.replace(/&(?:amp|quot|lt|gt|#39|#x27);/g, entity => ENTITIES[entity]);
}
