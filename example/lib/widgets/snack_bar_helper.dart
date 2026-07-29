import 'package:flutter/material.dart';

/// Displays a snackbar with the provided background color.
void showSnackBar(BuildContext context, String text, Color color) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(text),
      backgroundColor: color,
    ),
  );
}

/// Displays a success snackbar used by the example controls.
void showSnackBarSuc(BuildContext context, String text) {
  showSnackBar(context, text, Colors.lightBlue);
}

/// Displays an error snackbar used by the example controls.
void showSnackBarErr(BuildContext context, String text) {
  showSnackBar(context, text, Colors.redAccent);
}
