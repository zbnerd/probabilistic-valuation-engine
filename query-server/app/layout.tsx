export const metadata = {
  title: "V5 Query Server",
  description: "Query-only API server for V5 probabilistic valuation engine",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
