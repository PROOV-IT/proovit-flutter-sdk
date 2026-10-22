import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_partner_ready/main.dart';

void main() {
  testWidgets('partner quickstart displays the connection flow', (tester) async {
    await tester.pumpWidget(const PartnerReadyApp());
    expect(find.text('PROOV-IT · Capture SDK'), findsOneWidget);
    expect(find.text('Connexion / capabilities'), findsOneWidget);
    expect(find.text('Capture CameraX → reçu'), findsOneWidget);
  });
}
