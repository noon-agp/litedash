// Eliminar la cuenta de LiteDash Connect desde la web (lo pide Google Play).
// Lo usan /connect/delete-account/ (es) y /en/connect/delete-account/ (en).
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import {
  initializeAuth, browserSessionPersistence, browserPopupRedirectResolver,
  onAuthStateChanged, signInWithPopup, GoogleAuthProvider, signInWithEmailAndPassword,
  sendPasswordResetEmail, deleteUser, signOut,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js";

const CONFIG = {
  apiKey: "AIzaSyDX_EdZbfgSlt1jQRSpskfofEfu9W9C-u4",
  authDomain: "litedash.firebaseapp.com",
  projectId: "litedash",
  appId: "1:657731215906:web:a96e78bb191cafe66e607b",
};
const DOCS = "projects/litedash/databases/(default)/documents";
const COMMIT_URL = `https://firestore.googleapis.com/v1/${DOCS}:commit`;
// Firebase pide una entrada reciente para borrar; mejor pedirla antes de tocar Firestore.
const RECENT_LOGIN_MS = 4 * 60 * 1000;

const TEXT = {
  es: {
    deleting: "Eliminando tu cuenta…",
    wrongPassword: "Correo o contraseña incorrectos.",
    badEmail: "Escribe un correo válido.",
    missing: "Escribe tu correo y tu contraseña.",
    popupClosed: "Cerraste la ventana de Google antes de terminar.",
    popupBlocked: "Tu navegador bloqueó la ventana de Google. Permítela e inténtalo de nuevo.",
    network: "Sin conexión. Revisa tu internet e inténtalo de nuevo.",
    tooMany: "Demasiados intentos. Espera unos minutos e inténtalo de nuevo.",
    disabled: "Esta cuenta está desactivada.",
    unavailable: "Este método de entrada aún no está disponible.",
    relogin: "Por seguridad, vuelve a entrar y elimínala otra vez.",
    resetSent: "Si hay una cuenta con ese correo, te mandamos un enlace para cambiar la contraseña.",
    generic: "Algo salió mal. Inténtalo de nuevo.",
  },
  en: {
    deleting: "Deleting your account…",
    wrongPassword: "Wrong email or password.",
    badEmail: "Enter a valid email.",
    missing: "Enter your email and password.",
    popupClosed: "You closed the Google window before finishing.",
    popupBlocked: "Your browser blocked the Google window. Allow it and try again.",
    network: "No connection. Check your internet and try again.",
    tooMany: "Too many attempts. Wait a few minutes and try again.",
    disabled: "This account is disabled.",
    unavailable: "This sign-in method isn't available yet.",
    relogin: "For your security, sign in again and delete it again.",
    resetSent: "If there's an account with that email, we sent you a link to change the password.",
    generic: "Something went wrong. Please try again.",
  },
};
const lang = document.documentElement.lang.startsWith("en") ? "en" : "es";
const t = TEXT[lang];

const ERRORS = {
  "auth/invalid-credential": "wrongPassword",
  "auth/wrong-password": "wrongPassword",
  "auth/user-not-found": "wrongPassword",
  "auth/invalid-login-credentials": "wrongPassword",
  "auth/invalid-email": "badEmail",
  "auth/missing-password": "missing",
  "auth/missing-email": "badEmail",
  "auth/popup-closed-by-user": "popupClosed",
  "auth/cancelled-popup-request": "popupClosed",
  "auth/user-cancelled": "popupClosed",
  "auth/popup-blocked": "popupBlocked",
  "auth/network-request-failed": "network",
  "auth/too-many-requests": "tooMany",
  "auth/user-disabled": "disabled",
  "auth/operation-not-allowed": "unavailable",
  "auth/configuration-not-found": "unavailable",
  "auth/requires-recent-login": "relogin",
};

const $ = (id) => document.getElementById(id);
const views = document.querySelectorAll("[data-view]");
const message = $("message");
const form = $("email-form");
let done = false;

function show(view) {
  views.forEach((el) => { el.hidden = el.dataset.view !== view; });
}

function say(text, isError = false) {
  message.textContent = text || "";
  message.classList.toggle("error", isError);
}

function sayError(error) {
  console.error(error);
  say(t[ERRORS[error?.code]] || (navigator.onLine === false ? t.network : t.generic), true);
}

function busy(on) {
  document.querySelectorAll("#account button, #account input").forEach((el) => { el.disabled = on; });
}

function askConfirm(open) {
  $("ask").hidden = open;
  $("confirm").hidden = !open;
}

const app = initializeApp(CONFIG);
// Sesión solo en esta pestaña: al cerrarla no queda nadie adentro.
const auth = initializeAuth(app, {
  persistence: browserSessionPersistence,
  popupRedirectResolver: browserPopupRedirectResolver,
});
auth.languageCode = lang;

onAuthStateChanged(auth, (user) => {
  if (done) return;
  if (user) {
    $("email").textContent = user.email || user.displayName || user.uid;
    askConfirm(false);
    show("signed-in");
  } else {
    show("signed-out");
  }
});

async function attempt(action) {
  say("");
  busy(true);
  try {
    await action();
  } catch (error) {
    sayError(error);
  } finally {
    busy(false);
  }
}

$("google").addEventListener("click", () => attempt(() => {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  return signInWithPopup(auth, provider);
}));

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const email = form.email.value.trim();
  const password = form.password.value;
  if (!email || !password) return say(t.missing, true);
  attempt(async () => {
    await signInWithEmailAndPassword(auth, email, password);
    form.reset();
  });
});

$("forgot").addEventListener("click", () => {
  const email = form.email.value.trim();
  if (!email) return say(t.badEmail, true);
  attempt(async () => {
    await sendPasswordResetEmail(auth, email);
    say(t.resetSent);
  });
});

$("sign-out").addEventListener("click", () => attempt(() => signOut(auth)));
$("delete").addEventListener("click", () => { say(""); askConfirm(true); });
$("confirm-no").addEventListener("click", () => askConfirm(false));
$("confirm-yes").addEventListener("click", () => attempt(deleteAccount));

// Deja en Firestore solo {provider, since, deleted} y luego borra la cuenta de Auth.
async function tombstone(user) {
  const idToken = await user.getIdToken();
  const provider = user.providerData.some((p) => p.providerId === "google.com") ? "google" : "password";
  const since = new Date(user.metadata.creationTime).toISOString().slice(0, 10);
  const body = {
    writes: [{
      update: {
        name: `${DOCS}/accounts/${user.uid}`,
        fields: { provider: { stringValue: provider }, since: { stringValue: since } },
      },
      updateTransforms: [{ fieldPath: "deleted", setToServerValue: "REQUEST_TIME" }],
    }],
  };
  try {
    const response = await fetch(COMMIT_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${idToken}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    // Puede fallar si el documento ya estaba eliminado: se sigue igual.
    if (!response.ok) console.warn("Firestore commit", response.status, await response.text());
  } catch (error) {
    console.warn("Firestore commit", error);
  }
}

async function deleteAccount() {
  const user = auth.currentUser;
  if (!user) return show("signed-out");
  const lastSignIn = Date.parse(user.metadata.lastSignInTime);
  if (lastSignIn && Date.now() - lastSignIn > RECENT_LOGIN_MS) {
    await signOut(auth);
    return say(t.relogin, true);
  }
  say(t.deleting);
  await tombstone(user);
  try {
    await deleteUser(user);
  } catch (error) {
    if (error?.code === "auth/requires-recent-login") {
      await signOut(auth);
      return say(t.relogin, true);
    }
    throw error;
  }
  done = true;
  say("");
  show("done");
  await signOut(auth).catch(() => {});
}
