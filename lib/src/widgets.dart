import 'dart:io';
import 'package:flutter/material.dart';
import 'models.dart';

class CapturePreview extends StatelessWidget {
  const CapturePreview({super.key, required this.draft});
  final NativeCaptureDraft draft;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
    ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.file(File(draft.path), height: 220, width: double.infinity, fit: BoxFit.cover, semanticLabel: 'Aperçu de la photo capturée')),
    const SizedBox(height: 8), Text('SHA-256 : ${draft.sha256}', style: Theme.of(context).textTheme.bodySmall),
    const Text('Capture Android native signée localement'),
  ]);
}

class CaptureProgress extends StatelessWidget {
  const CaptureProgress({super.key, required this.value, required this.label});
  final double? value;
  final String label;
  @override
  Widget build(BuildContext context) => Semantics(liveRegion: true, label: label, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label), LinearProgressIndicator(value: value)]));
}

class ReceiptStatus extends StatelessWidget {
  const ReceiptStatus({super.key, required this.classification, required this.label});
  final TrustClassification classification;
  final String label;
  @override
  Widget build(BuildContext context) {
    final color = classification == TrustClassification.serverReceiptVerifiedFields ? Colors.green : classification == TrustClassification.pendingReconciliation ? Colors.orange : Colors.blueGrey;
    // Keep compatibility with the package's Flutter 3.19 minimum.
    // ignore: deprecated_member_use
    final cardColor = color.withOpacity(.12);
    return Semantics(liveRegion: true, child: Card(color: cardColor, child: Padding(padding: const EdgeInsets.all(12), child: Text(label, style: TextStyle(color: color, fontWeight: FontWeight.bold)))));
  }
}
