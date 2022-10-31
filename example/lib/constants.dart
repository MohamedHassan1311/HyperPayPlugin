import 'package:hyperpay/hyperpay.dart';

class TestConfig implements HyperpayConfig {
  @override
  String? creditcardEntityID = '8ac7a4c780f009370180f08e9e660111';
  @override
  String? madaEntityID = '8ac7a4c780f009370180f08f46220116';
  @override
  String? applePayEntityID = '';
  @override
  Uri checkoutEndpoint = _checkoutEndpoint;
  @override
  Uri statusEndpoint = _statusEndpoint;
  @override
  PaymentMode paymentMode = PaymentMode.test;
}

class LiveConfig implements HyperpayConfig {
  @override
  String? creditcardEntityID = '8acda4c98262a03e018274272d9a5c65';
  @override
  String? madaEntityID = '8acda4c98262a03e018274272d9a5c65';
  @override
  String? applePayEntityID = '8acda4cb83ef5ceb0183f767770a77a7';
  @override
  Uri checkoutEndpoint = _checkoutEndpoint;
  @override
  Uri statusEndpoint = _statusEndpoint;
  @override
  PaymentMode paymentMode = PaymentMode.live;
}

// Setup using your own endpoints.
// https://wordpresshyperpay.docs.oppwa.com/tutorials/mobile-sdk/integration/server.

String _host =  'dev.hyperpay.com';

Uri _checkoutEndpoint = Uri(
  scheme: 'https',
  host: _host,
  path: '/hyperpay-demo/getcheckoutid.php',
);
// http://demo.iselh.com/api/v1/hyperpay/checkouts
Uri _statusEndpoint = Uri(
  scheme: 'https',
  host: _host,
  path: '/hyperpay-demo/getpaymentstatus.php',

);
