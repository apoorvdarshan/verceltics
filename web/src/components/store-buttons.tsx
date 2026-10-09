import { APP_STORE_URL } from "@/lib/product";

function AppleMark() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24">
      <path
        d="M16.37 12.73c-.02-2.13 1.74-3.15 1.82-3.2-.99-1.45-2.54-1.65-3.09-1.67-1.32-.13-2.57.78-3.24.78-.67 0-1.7-.76-2.79-.74-1.44.02-2.77.84-3.51 2.12-1.5 2.6-.38 6.45 1.08 8.56.71 1.03 1.56 2.19 2.68 2.15 1.08-.04 1.48-.7 2.78-.7 1.3 0 1.66.7 2.8.68 1.16-.02 1.89-1.05 2.6-2.09.82-1.2 1.16-2.36 1.18-2.42-.03-.01-2.27-.87-2.31-3.47ZM14.22 6.48c.59-.72.99-1.71.88-2.71-.85.03-1.88.57-2.49 1.28-.55.63-1.03 1.64-.9 2.62.95.07 1.92-.48 2.51-1.19Z"
        fill="currentColor"
      />
    </svg>
  );
}

function PlayMark() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24">
      <path d="M4.2 2.6 13.6 12l-9.4 9.4a1.6 1.6 0 0 1-.7-1.35V3.95c0-.56.27-1.06.7-1.35Z" fill="#00d7fe" />
      <path d="m16.8 8.8-3.2 3.2-9.4-9.4c.5-.33 1.17-.38 1.74-.06l10.86 6.26Z" fill="#00f076" />
      <path d="m16.8 15.2-10.86 6.26c-.57.32-1.24.27-1.74-.06l9.4-9.4 3.2 3.2Z" fill="#ff3a44" />
      <path d="m16.8 8.8 3.42 1.97c1.07.62 1.07 1.84 0 2.46L16.8 15.2 13.6 12l3.2-3.2Z" fill="#ffd400" />
    </svg>
  );
}

// Separate store buttons: iOS downloads from the App Store, Android goes to the beta join steps.
export function StoreButtons({ tone = "ink", androidHref = "/#android-beta" }: { tone?: "ink" | "light"; androidHref?: string }) {
  return (
    <div className={`store-buttons store-buttons--${tone}`}>
      <a className="store-button store-button--apple" href={APP_STORE_URL} rel="noreferrer" target="_blank">
        <AppleMark />
        <span><small>Download on the</small><strong>App Store</strong></span>
      </a>
      <a className="store-button store-button--play" href={androidHref}>
        <PlayMark />
        <span><small>Android beta on</small><strong>Google Play</strong></span>
        <em>Beta</em>
      </a>
    </div>
  );
}
