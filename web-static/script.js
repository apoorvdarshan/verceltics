/* ════════════════════════════════════════════════════════════════
   VERCELTICS — LUMEN NOCTURNE
   Plain JS — no dependencies.
   ════════════════════════════════════════════════════════════════ */

(() => {
  const supportsHover = window.matchMedia("(hover: hover)").matches;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ─── Live UTC clock ─── */
  const clockEl = document.getElementById("utcClock");
  if (clockEl) {
    const tick = () => {
      const now = new Date();
      const hh = String(now.getUTCHours()).padStart(2, "0");
      const mm = String(now.getUTCMinutes()).padStart(2, "0");
      const ss = String(now.getUTCSeconds()).padStart(2, "0");
      clockEl.textContent = `${hh}:${mm}:${ss} UTC`;
    };
    tick();
    setInterval(tick, 1000);
  }

  /* ─── Footer year ─── */
  const yearEl = document.getElementById("year");
  if (yearEl) yearEl.textContent = new Date().getFullYear();

  /* ─── Custom cursor ─── */
  if (supportsHover && !reducedMotion) {
    const dot = document.getElementById("cursorDot");
    const ring = document.getElementById("cursorRing");
    if (dot && ring) {
      let mx = window.innerWidth / 2;
      let my = window.innerHeight / 2;
      let rx = mx, ry = my;
      let raf;

      const onMove = (e) => {
        mx = e.clientX;
        my = e.clientY;
        // dot follows mouse instantly
        dot.style.transform = `translate(${mx}px, ${my}px) translate(-50%, -50%)`;
        if (!raf) raf = requestAnimationFrame(loop);
      };

      const loop = () => {
        // ring trails with easing
        rx += (mx - rx) * 0.18;
        ry += (my - ry) * 0.18;
        ring.style.transform = `translate(${rx}px, ${ry}px) translate(-50%, -50%)`;
        if (Math.hypot(mx - rx, my - ry) > 0.4) {
          raf = requestAnimationFrame(loop);
        } else {
          raf = null;
        }
      };

      window.addEventListener("mousemove", onMove);

      // hover targets — scale ring up, hide dot
      const targets = document.querySelectorAll("[data-cursor]");
      targets.forEach((el) => {
        el.addEventListener("mouseenter", () => document.body.classList.add("cursor-active"));
        el.addEventListener("mouseleave", () => document.body.classList.remove("cursor-active"));
      });

      // hide cursor on mouseleave window
      document.addEventListener("mouseleave", () => {
        dot.style.opacity = "0";
        ring.style.opacity = "0";
      });
      document.addEventListener("mouseenter", () => {
        dot.style.opacity = "1";
        ring.style.opacity = "1";
      });
    }
  }

  /* ─── Scroll reveals ─── */
  const revealEls = document.querySelectorAll(".reveal");
  if (revealEls.length && "IntersectionObserver" in window && !reducedMotion) {
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("in");
            io.unobserve(entry.target);
          }
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.05 }
    );
    revealEls.forEach((el) => io.observe(el));
  } else {
    // No-IO fallback: just show all
    revealEls.forEach((el) => el.classList.add("in"));
  }

  /* ─── Bento card spotlight (track mouse for radial highlight) ─── */
  if (supportsHover && !reducedMotion) {
    const cards = document.querySelectorAll(".bento-card");
    cards.forEach((card) => {
      card.addEventListener("mousemove", (e) => {
        const rect = card.getBoundingClientRect();
        const x = ((e.clientX - rect.left) / rect.width) * 100;
        const y = ((e.clientY - rect.top) / rect.height) * 100;
        card.style.setProperty("--mx", `${x}%`);
        card.style.setProperty("--my", `${y}%`);
      });
    });
  }

  /* ─── Magnetic CTA: subtle attraction toward cursor ─── */
  if (supportsHover && !reducedMotion) {
    const magnets = document.querySelectorAll(".btn-accent");
    magnets.forEach((btn) => {
      btn.addEventListener("mousemove", (e) => {
        const r = btn.getBoundingClientRect();
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        const dx = (e.clientX - cx) * 0.18;
        const dy = (e.clientY - cy) * 0.18;
        btn.style.transform = `translate(${dx}px, ${dy}px)`;
      });
      btn.addEventListener("mouseleave", () => {
        btn.style.transform = "";
      });
    });
  }

  /* ─── Smooth anchor scrolling (with offset for sticky nav) ─── */
  document.querySelectorAll('a[href^="#"]').forEach((a) => {
    a.addEventListener("click", (e) => {
      const href = a.getAttribute("href");
      if (!href || href === "#") return;
      const target = document.querySelector(href);
      if (!target) return;
      e.preventDefault();
      const navH = document.querySelector(".nav")?.getBoundingClientRect().height || 0;
      const top = target.getBoundingClientRect().top + window.pageYOffset - navH - 16;
      window.scrollTo({ top, behavior: reducedMotion ? "auto" : "smooth" });
    });
  });
})();
