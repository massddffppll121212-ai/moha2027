const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_callvault_123";
const BOB_UID = "bob_callvault_456";
const CALL_DOC_ID = `${ALICE_UID}_Call_Incoming_966501234567_20261125_120000_mp3`;
const NOTE_DOC_ID = `${ALICE_UID}_Note_20261125_121000_mp3`;

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validCallPayload(uid, extra = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    fileName: "Call_Incoming_+966501234567_20261125_120000.mp3",
    callerName: "خالد العتيبي",
    phoneNumber: "+966501234567",
    direction: "INCOMING",
    durationMs: 42000,
    recordedAtMs: Date.now() - 5000,
    createdAt: now,
    updatedAt: now,
    ...extra,
  };
}

function validVoiceNotePayload(uid, extra = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    fileName: "VoiceNote_20261125_121000.mp3",
    title: "ملاحظة اجتماع المشروع",
    durationMs: 18500,
    recordedAtMs: Date.now() - 4000,
    createdAt: now,
    updatedAt: now,
    ...extra,
  };
}

test("Unauthenticated requests to calls and voice_notes fail", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("calls").where("userId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("voice_notes").where("userId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("calls").doc(CALL_DOC_ID).set(validCallPayload(ALICE_UID)));
  await assertFails(unauthDb.collection("voice_notes").doc(NOTE_DOC_ID).set(validVoiceNotePayload(ALICE_UID)));
});

test("Owner can CRUD calls with filtered userId query, transcription update, and Storage link", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const callsCol = aliceDb.collection("calls");
  const docRef = callsCol.doc(CALL_DOC_ID);

  await assertSucceeds(docRef.set(validCallPayload(ALICE_UID)));
  await assertSucceeds(callsCol.where("userId", "==", ALICE_UID).get());
  await assertFails(callsCol.get()); // Unfiltered list query must fail

  await assertSucceeds(
    docRef.update({
      transcript: "أنت: السلام عليكم\nالطرف الآخر: وعليكم السلام",
      summaryPoints: ["ترحيب", "تأكيد الموعد", "إغلاق"],
      actionItems: ["إرسال الملف غداً"],
      storagePath: `users/${ALICE_UID}/CallVault/Calls/Call_Incoming_+966501234567_20261125_120000.mp3`,
      storageDownloadUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/call.mp3",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("Owner can CRUD voice_notes with filtered userId query, title & transcription update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const notesCol = aliceDb.collection("voice_notes");
  const docRef = notesCol.doc(NOTE_DOC_ID);

  await assertSucceeds(docRef.set(validVoiceNotePayload(ALICE_UID)));
  await assertSucceeds(notesCol.where("userId", "==", ALICE_UID).get());
  await assertFails(notesCol.get()); // Unfiltered list query must fail

  await assertSucceeds(
    docRef.update({
      title: "ملاحظة اجتماع المشروع - محدثة",
      transcript: "تذكير بتسليم التصميم غداً.",
      summaryPoints: ["تسليم التصميم غداً"],
      actionItems: ["تجهيز ملف Figma"],
      storagePath: `users/${ALICE_UID}/CallVault/VoiceNotes/VoiceNote_20261125_121000.mp3`,
      storageDownloadUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/note.mp3",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("Cross-user isolation and schema hardening on calls and voice_notes", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("calls").doc(CALL_DOC_ID).set(validCallPayload(ALICE_UID));
    await ctx.firestore().collection("voice_notes").doc(NOTE_DOC_ID).set(validVoiceNotePayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("calls").doc(CALL_DOC_ID).get());
  await assertFails(bobDb.collection("calls").where("userId", "==", ALICE_UID).get());
  await assertFails(bobDb.collection("calls").doc(CALL_DOC_ID).delete());
  await assertFails(bobDb.collection("voice_notes").doc(NOTE_DOC_ID).get());
  await assertFails(bobDb.collection("voice_notes").where("userId", "==", ALICE_UID).get());
  await assertFails(bobDb.collection("voice_notes").doc(NOTE_DOC_ID).delete());

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  // Reject @DocumentId 'id' or invalid direction or empty voice note title
  await assertFails(
    aliceDb.collection("calls").doc("bad_call").set(validCallPayload(ALICE_UID, { id: "bad_call" }))
  );
  await assertFails(
    aliceDb.collection("calls").doc("bad_dir").set(validCallPayload(ALICE_UID, { direction: "MANUAL" }))
  );
  await assertFails(
    aliceDb.collection("voice_notes").doc("bad_title").set(validVoiceNotePayload(ALICE_UID, { title: "" }))
  );
  await assertFails(
    aliceDb.collection("voice_notes").doc(NOTE_DOC_ID).update({
      fileName: "hijacked.mp3",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});
